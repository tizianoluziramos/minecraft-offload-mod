package dev.remoteoffload.server.net;

import dev.remoteoffload.common.id.ClientId;
import dev.remoteoffload.common.net.ConnectionWriter;
import dev.remoteoffload.common.protocol.LogicalMessage;
import dev.remoteoffload.common.protocol.MalformedPacketException;
import dev.remoteoffload.common.protocol.PacketCodec;
import dev.remoteoffload.common.protocol.PacketFlags;
import dev.remoteoffload.common.protocol.PacketType;
import dev.remoteoffload.common.serial.Buf;
import dev.remoteoffload.common.task.LocalExecutors;
import dev.remoteoffload.common.task.TaskException;
import dev.remoteoffload.common.task.TaskType;
import dev.remoteoffload.server.ServerConfig;
import dev.remoteoffload.server.cache.ResultCache;
import dev.remoteoffload.server.security.AuthManager;
import dev.remoteoffload.server.security.RateLimiter;
import dev.remoteoffload.server.util.Log;
import dev.remoteoffload.server.work.ServerWorkers;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gestión de una conexión de cliente: handshake, auth, ciclo de lectura,
 * despacho de tareas y envío de resultados (single writer).
 */
public final class ClientConnection {

    private enum Phase { HELLO, AUTH, READY, CLOSED }

    private final Socket socket;
    private final ServerConfig cfg;
    private final AuthManager auth;
    private final RateLimiter limiter;
    private final ResultCache cache;
    private final ServerWorkers workers;
    private final ServerSocketListener parent;
    private final String remote;

    private final ConnectionWriter writer;
    private final PacketCodec.MessageReader reader;
    private final Semaphore inflight;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingTasks = ConcurrentHashMap.newKeySet();

    private volatile Phase phase = Phase.HELLO;
    private volatile ClientId clientId;
    private volatile byte[] challenge;
    private volatile long lastActivityNanos = System.nanoTime();
    private int malformedCount;

    private static final AtomicLong TASK_SEQ = new AtomicLong();

    public ClientConnection(Socket socket, ServerConfig cfg, AuthManager auth, RateLimiter limiter,
                            ResultCache cache, ServerWorkers workers, ServerSocketListener parent)
            throws IOException {
        this.socket = socket;
        this.cfg = cfg;
        this.auth = auth;
        this.limiter = limiter;
        this.cache = cache;
        this.workers = workers;
        this.parent = parent;
        this.remote = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress().getHostAddress();
        this.reader = new PacketCodec.MessageReader(cfg.maxFramePayload, cfg.maxPacketBytes);
        this.inflight = new Semaphore(cfg.maxConcurrentPerClient);
        this.writer = new ConnectionWriter(socket.getOutputStream(), "conn-" + porta());
        this.socket.setTcpNoDelay(true);
        this.socket.setSoTimeout(Math.max(1_000, cfg.heartbeatTimeoutMs / 2));
    }

    private int porta() {
        return socket.getPort();
    }

    public String remote() {
        return remote;
    }

    public ClientId clientId() {
        return clientId;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public int pendingTaskCount() {
        return pendingTasks.size();
    }

    public void run() {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            long handshakeDeadline = System.currentTimeMillis() + cfg.handshakeTimeoutMs;
            while (!closed.get()) {
                LogicalMessage m;
                try {
                    m = reader.readMessage(in);
                    if (m == null) {
                        continue;
                    }
                } catch (SocketTimeoutException e) {
                    if (phase == Phase.HELLO || phase == Phase.AUTH) {
                        if (System.currentTimeMillis() > handshakeDeadline) {
                            Log.info("handshake timeout %s", remote);
                            break;
                        }
                        continue;
                    }
                    long idle = System.nanoTime() - lastActivityNanos;
                    if (idle > cfg.heartbeatTimeoutMs * 1_000_000L) {
                        Log.info("heartbeat timeout %s (idle %d ms)", remote, idle / 1_000_000);
                        break;
                    }
                    continue;
                } catch (MalformedPacketException e) {
                    malformedCount++;
                    Log.warn("malformed packet %s code=%d (%s)", remote, e.code(), e.getMessage());
                    if (malformedCount >= 3) {
                        Log.warn("too many malformed packets, closing %s", remote);
                        break;
                    }
                    continue;
                } catch (IOException e) {
                    break;
                }
                lastActivityNanos = System.nanoTime();
                if (!handle(m)) {
                    break;
                }
            }
        } catch (IOException e) {
            Log.debug("connection %s ended: %s", remote, e.getMessage());
        } finally {
            cleanup();
        }
    }

    private boolean handle(LogicalMessage m) {
        PacketType type;
        try {
            type = PacketType.fromId(m.type);
        } catch (MalformedPacketException e) {
            return false;
        }

        switch (type) {
            case CLIENT_HELLO -> {
                if (phase != Phase.HELLO) return false;
                return handleHello(m);
            }
            case AUTH_RESPONSE -> {
                if (phase != Phase.AUTH) return false;
                return handleAuth(m);
            }
            case PING -> send(PacketType.PONG, m.taskId, pongPayload(m));
            case TASK_SUBMIT -> {
                if (phase != Phase.READY) return false;
                return handleTask(m);
            }
            case TASK_CANCEL -> {
                if (phase != Phase.READY) return false;
                cancelled.add(m.taskId);
                pendingTasks.remove(m.taskId);
            }
            case TASK_LIST -> send(PacketType.TASK_LIST, m.taskId, taskListPayload());
            case BYE -> {
                Log.info("bye %s (%s)", remote, utfOr(m));
                return false;
            }
            default -> {
                Log.warn("unexpected %s from %s in phase %s", type, remote, phase);
                return false;
            }
        }
        return true;
    }

    private boolean handleHello(LogicalMessage m) {
        Buf r = Buf.reader(m.payload);
        if (r.u16() < dev.remoteoffload.common.protocol.ProtocolConstants.MIN_PROTOCOL_VERSION) {
            sendError(0x0201, "protocol too old");
            send(PacketType.BYE, 0, Buf.writer().utf("incompatible protocol").toByteArray());
            return false;
        }
        this.clientId = ClientId.read(r);
        String name = r.utf();
        Log.info("hello %s client=%s name=%s", remote, clientId, name);

        Buf hello = Buf.writer();
        hello.u16(dev.remoteoffload.common.protocol.ProtocolConstants.PROTOCOL_VERSION)
                .u16(dev.remoteoffload.common.protocol.ProtocolConstants.MIN_PROTOCOL_VERSION)
                .u8(auth.requireAuth() ? 1 : 0)
                .utf("remote-offload-server")
                .u8(0x01); // caps: bit0 zlib transport
        send(PacketType.SERVER_WELCOME, 0, hello.toByteArray());

        if (!auth.requireAuth()) {
            phase = Phase.READY;
        } else {
            phase = Phase.AUTH;
            this.challenge = dev.remoteoffload.common.crypto.TokenAuth.challenge();
            send(PacketType.AUTH_CHALLENGE, 0, Buf.writer().bytes(challenge).toByteArray());
        }
        return true;
    }

    private boolean handleAuth(LogicalMessage m) {
        Buf r = Buf.reader(m.payload);
        ClientId id = ClientId.read(r);
        byte[] nonce = r.bytes(16);
        byte[] mac = r.bytes(32);
        if (r.hasRemaining() || !id.equals(clientId)) {
            return false;
        }
        boolean ok = auth.verify(id, challenge, nonce, mac);
        Buf result = Buf.writer().u8(ok ? 1 : 0).utf(ok ? "ok" : "auth failed");
        send(PacketType.AUTH_RESULT, 0, result.toByteArray());
        if (!ok) {
            return false;
        }
        phase = Phase.READY;
        return true;
    }

    private boolean handleTask(LogicalMessage m) {
        if (!limiter.allow("ip-" + remote)) {
            // rate limit por cliente real: ver nota; contamos una infracción
            return false;
        }
        if (m.payload.length < 1) {
            malformedCount++;
            return true;
        }
        Buf r = Buf.reader(m.payload);
        int taskTypeId = r.u8();
        byte[] req = r.bytes(r.remaining());
        TaskType type;
        try {
            type = TaskType.fromId(taskTypeId);
        } catch (IllegalArgumentException e) {
            send(PacketType.TASK_REJECT, m.taskId, rejectPayload(TaskException.ERR_UNSUPPORTED, "unsupported task"));
            return true;
        }
        if (req.length == 0) {
            send(PacketType.TASK_REJECT, m.taskId, rejectPayload(TaskException.ERR_MALFORMED, "empty request"));
            return true;
        }
        if (!LocalExecutors.has(type)) {
            send(PacketType.TASK_REJECT, m.taskId, rejectPayload(TaskException.ERR_UNSUPPORTED, "no executor"));
            return true;
        }

        String cacheKey = ResultCache.key(type, req);
        byte[] hit = cache.get(cacheKey);
        if (hit != null) {
            sendTaskResult(m, hit, 0, true);
            return true;
        }

        if (!inflight.tryAcquire()) {
            send(PacketType.TASK_REJECT, m.taskId, rejectPayload(TaskException.ERR_BUSY, "too many concurrent tasks"));
            return true;
        }
        pendingTasks.add(m.taskId);

        Buf accept = Buf.writer();
        accept.i64(cfg.workerTimeoutMs);
        send(PacketType.TASK_ACCEPT, m.taskId, accept.toByteArray());

        final long deadline = System.currentTimeMillis() + cfg.workerTimeoutMs;
        boolean ok = workers.submit(() -> execute(m, type, req, cacheKey, deadline));
        if (!ok) {
            inflight.release();
            pendingTasks.remove(m.taskId);
            send(PacketType.TASK_REJECT, m.taskId, rejectPayload(TaskException.ERR_BUSY, "server queue full"));
        }
        return true;
    }

    private void execute(LogicalMessage m, TaskType type, byte[] req, String cacheKey, long deadline) {
        try {
            if (cancelled.contains(m.taskId)) {
                send(PacketType.TASK_REJECT, m.taskId, rejectPayload(TaskException.ERR_TIMEOUT, "cancelled"));
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                send(PacketType.TASK_REJECT, m.taskId, rejectPayload(TaskException.ERR_TIMEOUT, "deadline exceeded"));
                return;
            }
            long t0 = System.nanoTime();
            byte[] resp = LocalExecutors.runLocal(type, req);
            long timeUs = (System.nanoTime() - t0) / 1000;
            if (isCacheable(type)) {
                cache.put(cacheKey, resp);
            }
            sendTaskResult(m, resp, timeUs, false);
        } catch (TaskException e) {
            send(PacketType.TASK_REJECT, m.taskId, rejectPayload(e.code(), e.getMessage()));
        } catch (Throwable t) {
            Log.error(t, "task internal error %s", m.taskId);
            send(PacketType.TASK_REJECT, m.taskId, rejectPayload(TaskException.ERR_INTERNAL, "internal error"));
        } finally {
            inflight.release();
            pendingTasks.remove(m.taskId);
            cancelled.remove(m.taskId);
        }
    }

    private static boolean isCacheable(TaskType type) {
        try {
            return LocalExecutors.handler(type).cacheable();
        } catch (TaskException e) {
            return false;
        }
    }

    private void sendTaskResult(LogicalMessage m, byte[] resp, long timeUs, boolean cacheHit) {
        Buf w = Buf.writer();
        w.u8(cacheHit ? 1 : 0)
                .u32l(timeUs)
                .bytes(resp);
        send(PacketType.TASK_RESULT, m.taskId, w.toByteArray());
    }

    public static byte[] rejectPayload(int code, String reason) {
        return Buf.writer().u8(code).utf(reason).toByteArray();
    }

    private byte[] pongPayload(LogicalMessage m) {
        Buf r = Buf.reader(m.payload);
        long seq = r.i64();
        long sentNanos = r.i64();
        return Buf.writer().i64(seq).i64(sentNanos).i64(System.nanoTime()).toByteArray();
    }

    private byte[] taskListPayload() {
        Buf w = Buf.writer();
        w.u8(TaskType.values().length);
        for (TaskType t : TaskType.values()) {
            w.u8(t.id());
            w.utf(t.name());
        }
        return w.toByteArray();
    }

    private void sendError(int code, String msg) {
        Buf w = Buf.writer();
        w.u16(code).utf(msg);
        send(PacketType.ERROR, 0, w.toByteArray());
    }

    private void send(PacketType type, long taskId, byte[] payload) {
        try {
            byte[] frame = PacketCodec.frameMessage(type.id(), taskId, (byte) 0, payload,
                    true, cfg.compressLevel, cfg.maxFramePayload);
            writer.submit(frame);
        } catch (IOException e) {
            Log.debug("send failed %s: %s", remote, e.getMessage());
        }
    }

    private static String utfOr(LogicalMessage m) {
        try {
            return Buf.reader(m.payload).utf();
        } catch (Exception e) {
            return "";
        }
    }

    /** Cierre ordenado desde fuera (shutdown del server). */
    public void closePeer() {
        cleanup();
    }

    private void cleanup() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        phase = Phase.CLOSED;
        for (long id : pendingTasks) {
            cancelled.add(id);
        }
        parent.onDisconnect(this);
        try {
            writer.close();
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        Log.info("closed %s", remote);
    }
}