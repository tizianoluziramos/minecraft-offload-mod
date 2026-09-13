package dev.remoteoffload.common.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Utilidades de I/O. */
public final class Mio {

    private Mio() {}

    /** Lee exactamente {@code len} bytes; lanza EOFException si no hay suficientes. */
    public static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(out, off, len - off);
            if (n < 0) {
                throw new EOFException("stream closed after " + off + " of " + len + " bytes");
            }
            off += n;
        }
        return out;
    }

    public static long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}