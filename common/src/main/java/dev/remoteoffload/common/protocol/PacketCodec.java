package dev.remoteoffload.common.protocol;

import dev.remoteoffload.common.util.Crc;
import dev.remoteoffload.common.util.Mio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Codec de capa de frame MKOF: tramado, fragmentación/reensamble,
 * compresión (zlib) e integridad (CRC32C).
 *
 * <p>Cada dirección del socket tiene un único hilo de escritura (atómic) y un
 * único hilo de lectura, por lo que el reensamble de fragmentos es secuencial
 * y se mantiene en un {@link MessageReader} por conexión.</p>
 */
public final class PacketCodec {

    private PacketCodec() {}

    /**
     * Escribe un mensaje lógico fragmentándolo si es mayor que {@code maxFramePayload}.
     */
    public static void writeMessage(OutputStream out, int type, long taskId, byte flags,
                                    byte[] logicalPayload, boolean compress, int compressionLevel,
                                    int maxFramePayload) throws IOException {
        byte[] message = frameMessage(type, taskId, flags, logicalPayload, compress, compressionLevel, maxFramePayload);
        synchronized (out) {
            out.write(message);
            out.flush();
        }
    }

    /**
     * Devuelve el byte[] con todas las tramas de un mensaje (útil para el
     * {@link dev.remoteoffload.common.net.ConnectionWriter}).
     */
    public static byte[] frameMessage(int type, long taskId, byte flags,
                                      byte[] logicalPayload, boolean compress, int compressionLevel,
                                      int maxFramePayload) throws IOException {
        byte[] wire = logicalPayload;
        if (compress && logicalPayload.length >= ProtocolConstants.COMPRESSION_MIN_PAYLOAD) {
            wire = deflate(logicalPayload, compressionLevel);
            flags |= PacketFlags.COMPRESSED;
        }

        if (wire.length == 0) {
            throw new IOException("zero-length message payload");
        }

        int total = Math.max(1, (wire.length + maxFramePayload - 1) / maxFramePayload);
        if (total > ProtocolConstants.MAX_FRAGMENTS) {
            throw new IOException("message too large to fragment (" + wire.length + " bytes)");
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(wire.length + 64);
        for (int seq = 0; seq < total; seq++) {
            int off = seq * maxFramePayload;
            int len = Math.min(maxFramePayload, wire.length - off);
            ByteBuffer header = PacketHeader.encode(
                    ProtocolConstants.PROTOCOL_VERSION, type, flags, taskId, seq, total, len);
            byte[] frame = new byte[ProtocolConstants.HEADER_SIZE + len + ProtocolConstants.CRC_SIZE];
            System.arraycopy(header.array(), 0, frame, 0, ProtocolConstants.HEADER_SIZE);
            System.arraycopy(wire, off, frame, ProtocolConstants.HEADER_SIZE, len);
            long crc = Crc.crc32c(frame, 0, ProtocolConstants.HEADER_SIZE + len);
            frame[ProtocolConstants.HEADER_SIZE + len] = (byte) (crc >>> 24);
            frame[ProtocolConstants.HEADER_SIZE + len + 1] = (byte) (crc >>> 16);
            frame[ProtocolConstants.HEADER_SIZE + len + 2] = (byte) (crc >>> 8);
            frame[ProtocolConstants.HEADER_SIZE + len + 3] = (byte) crc;
            out.write(frame);
        }
        return out.toByteArray();
    }

    /** Debe llamarse desde un único hilo de lectura por conexión. */
    public static final class MessageReader {
        private final int maxFramePayload;
        private final int maxPacketBytes;

        private int inType = -1;
        private long inTaskId = -1;
        private int inSeq = -1;
        private int inTotal = -1;
        private long fragmentWindowStart = -1;
        private byte[] reassembled;

        public MessageReader() {
            this(ProtocolConstants.MAX_FRAME_PAYLOAD_DEFAULT, ProtocolConstants.MAX_PACKET_BYTES_DEFAULT);
        }

        public MessageReader(int maxFramePayload, int maxPacketBytes) {
            this.maxFramePayload = maxFramePayload;
            this.maxPacketBytes = maxPacketBytes;
        }

        /**
         * Lee la siguiente trama y devuelve el mensaje lógico cuando se
         * completa (incluye no-fragmentado). Devuelve {@code null} si la trama
         * leída pertenece a un mensaje fragmentado aún incompleto.
         */
        public LogicalMessage readMessage(InputStream in) throws IOException {
            byte[] headerBytes = Mio.readFully(in, ProtocolConstants.HEADER_SIZE);
            PacketHeader h = PacketHeader.decode(ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN));
            validateFrame(h);

            int total = h.total;
            byte[] slice = Mio.readFully(in, h.payloadLength);
            byte[] crcBytes = Mio.readFully(in, ProtocolConstants.CRC_SIZE);
            verifyCrc(headerBytes, slice, crcBytes);

            if (total == 1) {
                byte[] payload = decompressIfNeeded(h.flags, slice);
                return new LogicalMessage(h.type, h.flags, h.taskId, payload);
            }

            if (h.seq == 0) {
                inType = h.type;
                inTaskId = h.taskId;
                inTotal = total;
                inSeq = 0;
                fragmentWindowStart = System.currentTimeMillis();
                reassembled = new byte[0];
            } else {
                if (h.type != inType || h.taskId != inTaskId || h.total != inTotal) {
                    throw new MalformedPacketException(MalformedPacketException.ERR_FRAGMENT,
                            "fragment context mismatch");
                }
                if (h.seq != inSeq + 1) {
                    throw new MalformedPacketException(MalformedPacketException.ERR_FRAGMENT,
                            "out-of-order fragment seq");
                }
                inSeq++;
            }

            if (reassembled.length + slice.length > maxPacketBytes) {
                throw new MalformedPacketException(MalformedPacketException.ERR_SIZE,
                        "reassembled message exceeds maxPacketBytes");
            }
            byte[] next = new byte[reassembled.length + slice.length];
            System.arraycopy(reassembled, 0, next, 0, reassembled.length);
            System.arraycopy(slice, 0, next, reassembled.length, slice.length);
            reassembled = next;

            if (inSeq == inTotal - 1) {
                byte[] payload = decompressIfNeeded(h.flags, reassembled);
                LogicalMessage m = new LogicalMessage(h.type, h.flags, h.taskId, payload);
                reset();
                return m;
            }
            return null;
        }

        private void reset() {
            inType = -1;
            inTaskId = -1;
            inSeq = -1;
            inTotal = -1;
            reassembled = null;
            fragmentWindowStart = -1;
        }

        private void validateFrame(PacketHeader h) {
            if (h.magic != (int) ProtocolConstants.MAGIC) {
                throw new MalformedPacketException(MalformedPacketException.ERR_MAGIC, "bad magic");
            }
            if (h.protocolVersion < ProtocolConstants.MIN_PROTOCOL_VERSION
                    || h.protocolVersion > ProtocolConstants.PROTOCOL_VERSION) {
                throw new MalformedPacketException(MalformedPacketException.ERR_VERSION,
                        "protocol version " + h.protocolVersion);
            }
            if (h.total == 0 || h.total > ProtocolConstants.MAX_FRAGMENTS) {
                throw new MalformedPacketException(MalformedPacketException.ERR_FRAGMENT,
                        "invalid fragment count " + h.total);
            }
            if (h.seq >= h.total) {
                throw new MalformedPacketException(MalformedPacketException.ERR_FRAGMENT,
                        "seq " + h.seq + " >= total " + h.total);
            }
            if (PacketFlags.isReserved(h.flags)) {
                throw new MalformedPacketException(MalformedPacketException.ERR_FLAGS,
                        "reserved flag bits set: 0x" + Integer.toHexString(h.flags));
            }
            if (h.payloadLength < 0 || h.payloadLength > maxFramePayload) {
                throw new MalformedPacketException(MalformedPacketException.ERR_SIZE,
                        "payload length " + h.payloadLength);
            }
            PacketType.fromId(h.type); // valida tipo conocido
        }

        private byte[] decompressIfNeeded(int flags, byte[] data) throws IOException {
            if ((flags & PacketFlags.COMPRESSED) == 0) {
                return data;
            }
            try {
                Inflater inf = new Inflater();
                inf.setInput(data);
                byte[] out = new byte[data.length * 4 + 64];
                int len = inf.inflate(out);
                if (!inf.finished()) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(out.length * 2);
                    bos.write(out, 0, len);
                    byte[] more = new byte[8192];
                    while (!inf.finished()) {
                        int n = inf.inflate(more);
                        if (n == 0 && !inf.finished()) {
                            inf.finished();
                            break;
                        }
                        bos.write(more, 0, n);
                    }
                    out = bos.toByteArray();
                } else {
                    byte[] trimmed = new byte[len];
                    System.arraycopy(out, 0, trimmed, 0, len);
                    out = trimmed;
                }
                inf.end();
                if (out.length > maxPacketBytes) {
                    throw new MalformedPacketException(MalformedPacketException.ERR_SIZE, "inflated too large");
                }
                return out;
            } catch (DataFormatException e) {
                throw new MalformedPacketException(MalformedPacketException.ERR_CRC, "invalid deflate stream");
            }
        }
    }

    public static byte[] deflate(byte[] data, int level) {
        Deflater d = new Deflater(level);
        d.setInput(data);
        d.finish();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(data.length / 2);
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            int n = d.deflate(buf);
            bos.write(buf, 0, n);
        }
        d.end();
        return bos.toByteArray();
    }

    private static void verifyCrc(byte[] header, byte[] payload, byte[] crcBytes) {
        long expected = Crc.crc32cTwo(header, payload, 0, payload.length);
        long got = ((long) (crcBytes[0] & 0xFF) << 24)
                | ((long) (crcBytes[1] & 0xFF) << 16)
                | ((long) (crcBytes[2] & 0xFF) << 8)
                | ((long) (crcBytes[3] & 0xFF));
        if (expected != got) {
            throw new MalformedPacketException(MalformedPacketException.ERR_CRC, "crc mismatch");
        }
    }
}