package dev.remoteoffload.common.serial;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Buffer binario pequeño con lectura/escritura explícita (big-endian).
 * Soporta strings UTF-8 con prefijo u32 y enteros de tamaño fijo y VarInt/VarLong.
 * Un buffer es writer O reader (se elige al construirlo).
 */
public final class Buf {

    private final boolean readMode;
    private ByteBuffer buf;

    private Buf(ByteBuffer b, boolean readMode) {
        this.buf = b;
        this.readMode = readMode;
    }

    public static Buf writer() {
        return new Buf(ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN), false);
    }

    public static Buf reader(byte[] data) {
        return new Buf(ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN), true);
    }

    public static Buf reader(ByteBuffer b) {
        return new Buf(b.order(ByteOrder.BIG_ENDIAN), true);
    }

    // ------------------------------------------------------------------ write

    public Buf u8(int v) {
        ensure(1);
        buf.put((byte) v);
        return this;
    }

    public Buf u16(int v) {
        ensure(2);
        buf.putShort((short) v);
        return this;
    }

    public Buf u32(int v) {
        ensure(4);
        buf.putInt(v);
        return this;
    }

    /** u32 que puede representar valores &gt; 2^31-1 (codificado en 4 bytes). */
    public Buf u32l(long v) {
        ensure(4);
        buf.putInt((int) v);
        return this;
    }

    public Buf i64(long v) {
        ensure(8);
        buf.putLong(v);
        return this;
    }

    public Buf bytes(byte[] b) {
        return bytes(b, 0, b.length);
    }

    public Buf bytes(byte[] b, int off, int len) {
        ensure(len);
        buf.put(b, off, len);
        return this;
    }

    public Buf utf(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        u32(b.length);
        return bytes(b);
    }

    public Buf varInt(int v) {
        int value = v;
        ensure(5);
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
        return this;
    }

    public Buf varLong(long v) {
        long value = v;
        ensure(10);
        while ((value & ~0x7FL) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
        return this;
    }

    // ------------------------------------------------------------------- read

    public int u8() {
        return buf.get() & 0xFF;
    }

    public int u16() {
        return buf.getShort() & 0xFFFF;
    }

    public int u32() {
        return buf.getInt();
    }

    public long u32l() {
        return buf.getInt() & 0xFFFFFFFFL;
    }

    public long i64() {
        return buf.getLong();
    }

    public byte[] bytes(int len) {
        byte[] out = new byte[len];
        buf.get(out);
        return out;
    }

    public String utf() {
        int len = u32();
        if (len < 0 || len > buf.remaining()) {
            throw new IllegalArgumentException("string length out of range: " + len);
        }
        return new String(bytes(len), StandardCharsets.UTF_8);
    }

    public int varInt() {
        int result = 0;
        int shift = 0;
        byte b;
        do {
            if (shift >= 32) {
                throw new IllegalArgumentException("malformed varint");
            }
            b = buf.get();
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    public long varLong() {
        long result = 0;
        int shift = 0;
        byte b;
        do {
            if (shift >= 64) {
                throw new IllegalArgumentException("malformed varlong");
            }
            b = buf.get();
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    // ------------------------------------------------------------------- misc

    public int remaining() {
        return buf.remaining();
    }

    public boolean hasRemaining() {
        return buf.hasRemaining();
    }

    public byte[] toByteArray() {
        ByteBuffer b = buf.duplicate();
        b.flip();
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private void ensure(int extra) {
        if (readMode) {
            throw new IllegalStateException("reader buffer");
        }
        if (buf.remaining() < extra) {
            int cap = Math.max(buf.capacity() * 2, buf.position() + extra);
            ByteBuffer n = ByteBuffer.allocate(cap).order(ByteOrder.BIG_ENDIAN);
            ByteBuffer cur = buf.duplicate();
            cur.flip();
            n.put(cur);
            buf = n;
        }
    }
}