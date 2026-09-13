package dev.remoteoffload.server;

import dev.remoteoffload.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Configuración del servidor (archivo properties). Todo valor se valida.
 */
public final class ServerConfig {

    public String bindAddress = "0.0.0.0";
    public int port = ProtocolConstants.DEFAULT_PORT;
    public String token = "";
    public boolean requireAuth = true;
    public Set<String> allowedClientIds = new LinkedHashSet<>();
    public int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
    public int maxConcurrentPerClient = 4;
    public int maxFramePayload = ProtocolConstants.MAX_FRAME_PAYLOAD_DEFAULT;
    public int maxPacketBytes = ProtocolConstants.MAX_PACKET_BYTES_DEFAULT;
    public int rateLimitPerSec = 120;
    public int heartbeatTimeoutMs = 10_000;
    public int handshakeTimeoutMs = 10_000;
    public int workerTimeoutMs = 30_000;
    public int compressLevel = 6;
    public int maxPending = 1024;
    public Path cacheDir = Path.of("cache");
    public long cacheMaxMB = 256;
    public Path logFile = null;
    public boolean console = true;

    public static ServerConfig load(Path file) throws IOException {
        ServerConfig c = new ServerConfig();
        Properties p = new Properties();
        if (file != null && Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        if (p.containsKey("bind")) c.bindAddress = p.getProperty("bind").trim();
        if (p.containsKey("port")) c.port = Integer.parseInt(p.getProperty("port").trim());
        if (p.containsKey("token")) c.token = p.getProperty("token");
        if (p.containsKey("requireAuth")) c.requireAuth = Boolean.parseBoolean(p.getProperty("requireAuth"));
        if (p.containsKey("allowedClientIds")) {
            for (String id : p.getProperty("allowedClientIds").split(",")) {
                String t = id.trim();
                if (!t.isEmpty()) c.allowedClientIds.add(t);
            }
        }
        if (p.containsKey("workers")) c.workers = Integer.parseInt(p.getProperty("workers").trim());
        if (p.containsKey("maxConcurrentPerClient")) c.maxConcurrentPerClient = Integer.parseInt(p.getProperty("maxConcurrentPerClient").trim());
        if (p.containsKey("maxFramePayload")) c.maxFramePayload = Integer.parseInt(p.getProperty("maxFramePayload").trim());
        if (p.containsKey("maxPacketBytes")) c.maxPacketBytes = Integer.parseInt(p.getProperty("maxPacketBytes").trim());
        if (p.containsKey("rateLimitPerSec")) c.rateLimitPerSec = Integer.parseInt(p.getProperty("rateLimitPerSec").trim());
        if (p.containsKey("heartbeatTimeoutMs")) c.heartbeatTimeoutMs = Integer.parseInt(p.getProperty("heartbeatTimeoutMs").trim());
        if (p.containsKey("handshakeTimeoutMs")) c.handshakeTimeoutMs = Integer.parseInt(p.getProperty("handshakeTimeoutMs").trim());
        if (p.containsKey("workerTimeoutMs")) c.workerTimeoutMs = Integer.parseInt(p.getProperty("workerTimeoutMs").trim());
        if (p.containsKey("compressLevel")) c.compressLevel = Integer.parseInt(p.getProperty("compressLevel").trim());
        if (p.containsKey("maxPending")) c.maxPending = Integer.parseInt(p.getProperty("maxPending").trim());
        if (p.containsKey("cacheDir")) c.cacheDir = Path.of(p.getProperty("cacheDir").trim());
        if (p.containsKey("cacheMaxMB")) c.cacheMaxMB = Long.parseLong(p.getProperty("cacheMaxMB").trim());
        if (p.containsKey("logFile")) c.logFile = Path.of(p.getProperty("logFile").trim());
        if (p.containsKey("console")) c.console = Boolean.parseBoolean(p.getProperty("console").trim());
        c.validate();
        return c;
    }

    public void validate() {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
        if (workers < 1 || workers > 512) throw new IllegalArgumentException("workers out of range");
        if (maxConcurrentPerClient < 1 || maxConcurrentPerClient > 64) throw new IllegalArgumentException("maxConcurrentPerClient out of range");
        if (maxFramePayload < 1024 || maxFramePayload > ProtocolConstants.MAX_FRAME_PAYLOAD_HARD) throw new IllegalArgumentException("maxFramePayload out of range");
        if (maxPacketBytes < maxFramePayload || maxPacketBytes > ProtocolConstants.MAX_PACKET_BYTES_HARD) throw new IllegalArgumentException("maxPacketBytes out of range");
        if (rateLimitPerSec < 1 || rateLimitPerSec > 100_000) throw new IllegalArgumentException("rateLimitPerSec out of range");
        if (heartbeatTimeoutMs < 1000 || heartbeatTimeoutMs > 300_000) throw new IllegalArgumentException("heartbeatTimeoutMs out of range");
        if (workerTimeoutMs < 1000 || workerTimeoutMs > 600_000) throw new IllegalArgumentException("workerTimeoutMs out of range");
        if (compressLevel < 1 || compressLevel > 9) throw new IllegalArgumentException("compressLevel out of range");
        if (cacheMaxMB < 1 || cacheMaxMB > 1_000_000) throw new IllegalArgumentException("cacheMaxMB out of range");
        if (token == null) token = "";
    }
}