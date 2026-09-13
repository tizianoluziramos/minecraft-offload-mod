package dev.remoteoffload.runtime;

import dev.remoteoffload.common.net.ConnectionWriter;
import dev.remoteoffload.common.protocol.LogicalMessage;
import dev.remoteoffload.common.protocol.PacketCodec;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Conexión socket del CLIENTE (rol activo). Encapsula el codec MKOF y la
 * escritura single-writer. Es pura Java (sin hilos de Minecraft).
 */
public final class ClientConnection implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final ConnectionWriter writer;
    private final PacketCodec.MessageReader reader;
    private final boolean compression;
    private final int compressLevel;
    private final int maxFramePayload;

    private ClientConnection(Socket socket, boolean compression, int compressLevel,
                             int maxFramePayload, int maxPacketBytes) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.socket.setSoTimeout(2000);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.writer = new ConnectionWriter(socket.getOutputStream(), "offload");
        this.reader = new PacketCodec.MessageReader(maxFramePayload, maxPacketBytes);
        this.compression = compression;
        this.compressLevel = compressLevel;
        this.maxFramePayload = maxFramePayload;
    }

    /** Conecta con timeout; el hilo del juego nunca espera aquí si usas un executor. */
    public static ClientConnection connect(String host, int port, int timeoutMs,
                                           boolean compression, int maxPacketBytes) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        return new ClientConnection(s, compression, 6,
                dev.remoteoffload.common.protocol.ProtocolConstants.MAX_FRAME_PAYLOAD_DEFAULT,
                maxPacketBytes);
    }

    public void send(int type, long taskId, byte[] payload) throws IOException {
        byte[] framed = PacketCodec.frameMessage(type, taskId, (byte) 0, payload,
                compression, compressLevel, maxFramePayload);
        writer.submit(framed);
    }

    /**
     * Lee el siguiente mensaje lógico completo (bloqueante, con SO_TIMEOUT).
     */
    public LogicalMessage read() throws IOException {
        while (true) {
            LogicalMessage m = reader.readMessage(in);
            if (m != null) {
                return m;
            }
        }
    }

    @Override
    public void close() {
        writer.close();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public boolean isAlive() {
        return !socket.isClosed();
    }
}