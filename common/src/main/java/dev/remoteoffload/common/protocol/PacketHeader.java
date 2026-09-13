package dev.remoteoffload.common.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Cabecera fija de 24 bytes:
 * magic(4) proto(2) type(1) flags(1) taskId(8) seq(2) total(2) payloadLen(4).
 */
public final class PacketHeader {

    public int magic;
    public int protocolVersion;
    public int type;
    public int flags;
    public long taskId;
    public int seq;
    public int total;
    public int payloadLength;

    public static ByteBuffer encode(int protoVersion, int type, int flags, long taskId,
                                    int seq, int total, int payloadLength) {
        ByteBuffer b = ByteBuffer.allocate(ProtocolConstants.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        b.putInt(0x4D4B4F46);
        b.putShort((short) protoVersion);
        b.put((byte) type);
        b.put((byte) flags);
        b.putLong(taskId);
        b.putShort((short) seq);
        b.putShort((short) total);
        b.putInt(payloadLength);
        b.flip();
        return b;
    }

    /**
     * Lee los 24 bytes de cabecera. No valida (validación en {@link PacketCodec}).
     */
    public static PacketHeader decode(ByteBuffer header) {
        if (header.remaining() < ProtocolConstants.HEADER_SIZE) {
            throw new MalformedPacketException(MalformedPacketException.ERR_SIZE, "header short");
        }
        ByteBuffer b = header.order(ByteOrder.BIG_ENDIAN);
        PacketHeader h = new PacketHeader();
        h.magic = b.getInt();
        h.protocolVersion = b.getShort() & 0xFFFF;
        h.type = b.get() & 0xFF;
        h.flags = b.get() & 0xFF;
        h.taskId = b.getLong();
        h.seq = b.getShort() & 0xFFFF;
        h.total = b.getShort() & 0xFFFF;
        h.payloadLength = b.getInt();
        return h;
    }
}