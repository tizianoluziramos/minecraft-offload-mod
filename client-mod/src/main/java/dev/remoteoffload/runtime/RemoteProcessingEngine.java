package dev.remoteoffload.runtime;

import dev.remoteoffload.common.crypto.TokenAuth;
import dev.remoteoffload.common.id.ClientId;
import dev.remoteoffload.common.net.ConnectionState;
import dev.remoteoffload.common.protocol.LogicalMessage;
import dev.remoteoffload.common.protocol.PacketType;
import dev.remoteoffload.common.serial.Buf;
import dev.remoteoffload.common.stat.ProfilingStats;
import dev.remoteoffload.common.task.LocalExecutors;
import dev.remoteoffload.common.task.TaskException;
import dev.remoteoffload.common.task.TaskType;
import dev.remoteoffload.config.RemoteProcessingConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Motor de offload del cliente. Red y CPU en sus propios hilos; nunca se
 * llama desde el render/tick thread excepto métodos triviales (state(), stats()).
 *
 * <p>Garantías:
 * <ul>
 *   <li>Si el server no está disponible o una tarea falla/tarda → fallback
 *       LOCAL automático con la MISMA implementación (common) → sin crash.</li>
 *   <li>Reconexión automática con backoff exponencial.</li>
 *   <li>Todas las operaciones de red asíncronas.</li>
 * </ul>
 */
public final class RemoteProcessingEngine {

    private final RemoteProcessingConfig config;
    private final ProfilingStats stats = new ProfilingStats();
    private final ExecutorService localExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService netExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<Long, PendingTask> pending = new ConcurrentHashMap<>();
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicLong taskIdSeq = new AtomicLong(1);
    private final AtomicLong pingSeq = new AtomicLong(1);

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile String lastError = "";
    private volatile String serverInfo = "";
    private volatile ClientConnection conn;
    private volatile long configEpoch;
    private volatile boolean closed;
    private volatile String clientName = "remote-offload-mod";

    private RemoteProcessingEngine(RemoteProcessingConfig cfg) {
        this.config = cfg;
        Thread.ofPlatform().name("offload-session").start(this::sessionLoop);
        Thread.ofPlatform().name("offload-watchdog").daemon(true).start(this::watchdogLoop);
    }

    // Singleton con init perezoso (llamado desde el entrypoint, hilo no crítico).
    private static volatile RemoteProcessingEngine INSTANCE;

    public static synchronized RemoteProcessingEngine init(RemoteProcessingConfig cfg) {
        if (INSTANCE == null) {
            INSTANCE = new RemoteProcessingEngine(cfg);
        }
        return INSTANCE;
    }

    public static RemoteProcessingEngine instance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ API

    public RemoteProcessingConfig config() {
        return config;
    }

    public ProfilingStats stats() {
        return stats;
    }

    public ConnectionState state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    public String serverInfo() {
        return serverInfo;
    }

    public boolean isReady() {
        return state == ConnectionState.READY;
    }

    public long lastRttUs() {
        return stats.rttEmaUs();
    }

    public int pendingCount() {
        return pending.size() + inflight.get();
    }

    /** Aplica nueva config y reinicia la sesión si cambió algo relevante. */
    public String applyConfig(RemoteProcessingConfig copy) {
        RemoteProcessingConfig target = copy.copy();
        boolean addrChanged = !target.host.equals(config.host) || target.port != config.port
                || !target.token.equals(config.token) || target.enabled != config.enabled
                || target.timeoutMs != config.timeoutMs || target.maxPacketBytes != config.maxPacketBytes;
        RemoteProcessingConfig old = config.copy();
        config.host = target.host;
        config.port = target.port;
        config.token = target.token;
        config.timeoutMs = target.timeoutMs;
        config.maxConcurrentTasks = target.maxConcurrentTasks;
        config.compression = target.compression;
        config.showLatency = target.showLatency;
        config.showStatus = target.showStatus;
        config.autoReconnect = target.autoReconnect;
        config.maxPacketBytes = target.maxPacketBytes;
        config.enabled = target.enabled;
        config.clientId = target.clientId;
        config.normalize();
        if (addrChanged) {
            bumpAndReconnect();
        }
        return null;
    }

    /**
     * Envía una tarea al server; si no hay server, la resuelve LOCALMENTE.
     * El caller recibe el payload de respuesta crudo.
     */
    public CompletableFuture<byte[]> submit(TaskType type, byte[] request) {
        stats.forType(type.name()).submits.increment();
        long id = taskIdSeq.getAndIncrement();
        PendingTask pt = new PendingTask(id, type, request);
        pt.submittedAtMs = System.currentTimeMillis();
        pt.deadlineMs = pt.submittedAtMs + config.timeoutMs + 30_000;

        if (!config.enabled || !isReady()) {
            runLocal(pt, "server-not-ready");
            return pt.future;
        }
        if (inflight.get() >= config.maxConcurrentTasks) {
            runLocal(pt, "busy");
            return pt.future;
        }
        inflight.incrementAndGet();
        pt.remotePath = true;
        pending.put(id, pt);

        Buf w = Buf.writer();
        w.u8(type.id()).bytes(request);
        byte[] payload = w.toByteArray();
        stats.addTx(payload.length);
        try {
            conn.send(PacketType.TASK_SUBMIT.id(), id, payload);
        } catch (Exception e) {
            inflight.decrementAndGet();
            pending.remove(id);
            runLocal(pt, "send-failed");
        }
        return pt.future;
    }

    public void cancelTask(long taskId) {
        PendingTask pt = pending.get(taskId);
        if (pt == null) {
            return;
        }
        pt.deadlineMs = 0; // forzar watchdog → local fallback
        try {
            conn.send(PacketType.TASK_CANCEL.id(), taskId, Buf.writer().utf("cancel").toByteArray());
        } catch (Exception ignored) {
        }
    }

    public int cancelAll() {
        int n = 0;
        for (Long id : pending.keySet()) {
            cancelTask(id);
            n++;
        }
        return n;
    }

    /**
     * Prueba de conexión no bloqueante (para la pantalla de config).
     * Devuelve un resumen legible.
     */
    public CompletableFuture<String> testConnection(RemoteProcessingConfig target) {
        return CompletableFuture.supplyAsync(() -> {
            RemoteProcessingConfig t = target.copy();
            t.normalize();
            String warn = t.validateHostWarnings();
            if (warn != null) {
                return "Configuración inválida: " + warn;
            }
            ClientConnection c = null;
            try {
                c = ClientConnection.connect(t.host, t.port, t.timeoutMs, true, t.maxPacketBytes);
                sendHello(c, t.clientId);
                HandshakeOutcome out = handshake(c, t, ClientId.fromHex(t.clientId));
                if (out.ready) {
                    return "OK: conectado a '" + out.serverName + "' (protocolo " + out.serverProto + ")";
                }
                return "Server contactado pero handshake incompleto: " + out.reason;
            } catch (IOException e) {
                return "FALLO: " + e.getMessage();
            } catch (Exception e) {
                return "FALLO: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }, netExecutor);
    }

    // ------------------------------------------------------------ handshake

    private static void sendHello(ClientConnection c, String clientIdHex) throws IOException {
        Buf w = Buf.writer();
        w.u16(dev.remoteoffload.common.protocol.ProtocolConstants.PROTOCOL_VERSION);
        w.bytes(ClientId.fromHex(clientIdHex).bytes());
        w.utf("remote-offload-mod");
        w.u8(0x01); // caps: zlib transport
        c.send(PacketType.CLIENT_HELLO.id(), 0, w.toByteArray());
    }

    private static final class HandshakeOutcome {
        boolean ready;
        String serverName = "";
        int serverProto;
        String reason = "";
    }

    private static HandshakeOutcome handshake(ClientConnection c, RemoteProcessingConfig cfg,
                                              ClientId clientId) throws IOException {
        HandshakeOutcome out = new HandshakeOutcome();
        long deadline = System.currentTimeMillis() + cfg.timeoutMs * 2;
        byte[] challenge = null;
        byte[] nonce = null;

        while (System.currentTimeMillis() < deadline) {
            LogicalMessage m;
            try {
                m = c.read();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            PacketType t = PacketType.fromId(m.type);
            switch (t) {
                case SERVER_WELCOME -> {
                    Buf r = Buf.reader(m.payload);
                    out.serverProto = r.u16();
                    r.u16(); // minProto
                    int authRequired = r.u8();
                    out.serverName = r.utf();
                    if (authRequired == 0) {
                        out.ready = true;
                        return out;
                    }
                }
                case AUTH_CHALLENGE -> {
                    challenge = m.payload.clone();
                    nonce = TokenAuth.nonce();
                    byte[] mac = TokenAuth.computeMac(
                            cfg.token.getBytes(StandardCharsets.UTF_8), challenge, nonce);
                    Buf w = Buf.writer();
                    w.bytes(clientId.bytes()).bytes(nonce).bytes(mac);
                    c.send(PacketType.AUTH_RESPONSE.id(), 0, w.toByteArray());
                }
                case AUTH_RESULT -> {
                    Buf r = Buf.reader(m.payload);
                    boolean ok = r.u8() == 1;
                    String reason = r.hasRemaining() ? r.utf() : "";
                    if (!ok) {
                        out.reason = "auth rechazada: " + reason;
                        return out;
                    }
                    out.ready = true;
                    out.reason = "";
                    return out;
                }
                case ERROR -> {
                    out.reason = "server error";
                    return out;
                }
                default -> {
                    // ignorado durikant handshake
                }
            }
        }
        out.reason = "timeout en handshake";
        return out;
    }

    // ------------------------------------------------------------ sesión

    private void sessionLoop() {
        int backoffSec = 1;
        long epoch = 0;
        while (!closed) {
            if (!config.enabled) {
                state = ConnectionState.DISCONNECTED;
                sleep(250);
                continue;
            }
            if (epoch != configEpoch) {
                epoch = configEpoch;
                closeConn();
            }
            try {
                state = ConnectionState.CONNECTING;
                ClientConnection c = ClientConnection.connect(config.host, config.port,
                        config.timeoutMs, true, config.maxPacketBytes);
                conn = c;
                state = ConnectionState.HANDSHAKE;
                sendHello(c, config.clientId);
                HandshakeOutcome out = handshake(c, config, ClientId.fromHex(config.clientId));
                if (!out.ready) {
                    throw new IOException(out.reason.isEmpty() ? "handshake incompleto" : out.reason);
                }
                serverInfo = out.serverName + " (proto " + out.serverProto + ")";
                state = ConnectionState.READY;
                lastError = "";
                backoffSec = 1;
                startPing(c);
                readLoop(c, epoch);
            } catch (IOException | RuntimeException e) {
                lastError = String.valueOf(e.getMessage());
                if (closed) {
                    break;
                }
            } finally {
                closeConn();
                failAllToLocal("connection-lost");
                if (state == ConnectionState.READY || state == ConnectionState.AUTH) {
                    state = ConnectionState.BACKOFF;
                } else if (state != ConnectionState.BACKOFF) {
                    state = ConnectionState.DISCONNECTED;
                }
            }
            if (!config.autoReconnect || closed) {
                if (!closed && !config.enabled) {
                    state = ConnectionState.DISCONNECTED;
                }
                break;
            }
            state = ConnectionState.BACKOFF;
            sleep(Math.min(backoffSec, 60) * 1000L);
            backoffSec = Math.min(backoffSec * 2, 60);
        }
    }

    private void readLoop(ClientConnection c, long epoch) throws IOException {
        long lastActivity = System.nanoTime();
        while (!closed && epoch == configEpoch && conn == c) {
            LogicalMessage m;
            try {
                m = c.read();
            } catch (java.net.SocketTimeoutException e) {
                long idle = System.nanoTime() - lastActivity;
                if (idle > config.timeoutMs * 2L * 1_000_000) {
                    throw new IOException("idle timeout");
                }
                continue;
            }
            lastActivity = System.nanoTime();
            handle(c, m);
        }
    }

    private void handle(ClientConnection c, LogicalMessage m) {
        try {
            PacketType t = PacketType.fromId(m.type);
            switch (t) {
                case TASK_RESULT -> {
                    PendingTask pt = pending.remove(m.taskId);
                    if (pt == null) {
                        return;
                    }
                    inflight.decrementAndGet();
                    Buf r = Buf.reader(m.payload);
                    int cacheHit = r.u8();
                    long processUs = r.u32l();
                    byte[] resp = r.bytes(r.remaining());
                    if (cacheHit == 1) {
                        stats.forType(pt.type.name()).cacheHits.increment();
                    }
                    stats.forType(pt.type.name()).remote.increment();
                    stats.forType(pt.type.name()).remoteTotalUs.add(processUs);
                    stats.addRx(resp.length + 8);
                    if (pt.deadlineMs > 0) {
                        pt.future.complete(resp);
                    }
                }
                case TASK_REJECT -> {
                    PendingTask pt = pending.remove(m.taskId);
                    if (pt == null) {
                        return;
                    }
                    inflight.decrementAndGet();
                    Buf r = Buf.reader(m.payload);
                    int code = r.u8();
                    String reason = r.remaining() > 0 ? r.utf() : "";
                    stats.forType(pt.type.name()).errors.increment();
                    runLocal(pt, "reject(" + code + "): " + reason);
                }
                case PONG -> {
                    Buf r = Buf.reader(m.payload);
                    long seq = r.i64();
                    long sentNanos = r.i64();
                    r.i64(); // serverNow
                    long rttUs = (System.nanoTime() - sentNanos) / 1000;
                    stats.recordRtt(rttUs);
                }
                case TASK_LIST -> storeTaskList(m);
                case ERROR -> {
                    Buf r = Buf.reader(m.payload);
                    int code = r.u16();
                    String msg = r.hasRemaining() ? r.utf() : "";
                    lastError = "protocol error " + "0x" + Integer.toHexString(code) + ": " + msg;
                }
                case BYE -> throw new RuntimeException("server bye");
                default -> {
                    // ignoramos lo que no esperamos en esta fase
                }
            }
        } catch (Exception e) {
            lastError = "error en respuesta: " + e.getMessage();
        }
    }

    private volatile String[] knownTasks = new String[0];

    private void storeTaskList(LogicalMessage m) {
        try {
            Buf r = Buf.reader(m.payload);
            int n = r.u8();
            String[] arr = new String[n];
            for (int i = 0; i < n; i++) {
                r.u8();
                arr[i] = r.utf();
            }
            knownTasks = arr;
        } catch (Exception ignored) {
        }
    }

    public String[] knownTasks() {
        return knownTasks;
    }

    private void startPing(ClientConnection c) {
        Thread.ofVirtual().name("offload-ping").start(() -> {
            while (conn == c && !closed && isReady()) {
                long seq = pingSeq.getAndIncrement();
                long sent = System.nanoTime();
                Buf w = Buf.writer().i64(seq).i64(sent);
                try {
                    c.send(PacketType.PING.id(), seq, w.toByteArray());
                    Thread.sleep(config.pingIntervalMs());
                } catch (InterruptedException e) {
                    return;
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    private void watchdogLoop() {
        while (!closed) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            for (PendingTask pt : pending.values()) {
                if (pt.remotePath && pt.deadlineMs > 0 && now > pt.deadlineMs) {
                    if (pending.remove(pt.taskId, pt)) {
                        inflight.decrementAndGet();
                        try {
                            conn.send(PacketType.TASK_CANCEL.id(), pt.taskId, Buf.writer().utf("cancel").toByteArray());
                        } catch (Exception ignored) {
                        }
                        runLocal(pt, "timeout-remote");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ fallback

    private void runLocal(PendingTask pt, String why) {
        stats.forType(pt.type.name()).local.increment();
        long t0 = System.nanoTime();
        localExecutor.submit(() -> {
            try {
                byte[] resp = LocalExecutors.runLocal(pt.type, pt.request);
                long us = (System.nanoTime() - t0) / 1000;
                stats.forType(pt.type.name()).localTotalUs.add(us);
                pt.future.complete(resp);
            } catch (TaskException e) {
                pt.future.completeExceptionally(new RemoteTaskException(e.code(), e.getMessage()));
            }
        });
    }

    public void failAllToLocal(String why) {
        for (PendingTask pt : pending.values()) {
            if (pending.remove(pt.taskId, pt)) {
                inflight.decrementAndGet();
                runLocal(pt, why);
            }
        }
    }

    // ------------------------------------------------------------- infra

    private void bumpAndReconnect() {
        configEpoch++;
        closeConn();
    }

    private void closeConn() {
        ClientConnection c = conn;
        conn = null;
        if (c != null) {
            c.close();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        closed = true;
        bumpAndReconnect();
        failAllToLocal("shutdown");
        localExecutor.shutdown();
        netExecutor.shutdown();
    }

    /** Seguro para shutdown hook. */
    public static void shutdownSafe() {
        RemoteProcessingEngine e = RemoteProcessingEngine.instance();
        if (e != null) {
            e.shutdown();
        }
    }
}