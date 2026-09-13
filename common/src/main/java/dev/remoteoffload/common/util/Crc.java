package dev.remoteoffload.common.util;

import java.util.zip.CRC32C;

/** Cálculo de CRC32C sobre streams de bytes. */
public final class Crc {

    private Crc() {}

    public static long crc32c(byte[] data, int off, int len) {
        CRC32C c = new CRC32C();
        c.update(data, off, len);
        return c.getValue();
    }

    /** CRC de la concatenación de a[0..) y b[off..off+len). */
    public static long crc32cTwo(byte[] a, byte[] b, int bOff, int bLen) {
        CRC32C c = new CRC32C();
        c.update(a, 0, a.length);
        c.update(b, bOff, bLen);
        return c.getValue();
    }
}