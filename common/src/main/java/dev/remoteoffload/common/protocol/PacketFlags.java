package dev.remoteoffload.common.protocol;

/** Bits de flags del frame MKOF. */
public final class PacketFlags {

    private PacketFlags() {}

    public static final int COMPRESSED = 0x01;
    public static final int ENCRYPTED = 0x02;  // reservado → protocolo v2
    public static final int URGENT = 0x04;

    public static final int ALLOWED = COMPRESSED | ENCRYPTED | URGENT;

    public static boolean isReserved(int flags) {
        return (flags & ~ALLOWED) != 0;
    }
}