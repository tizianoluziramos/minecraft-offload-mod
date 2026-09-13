package dev.remoteoffload.common;

import dev.remoteoffload.common.serial.Buf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BufTest {

    @Test
    void fixedWidthRoundTrip() {
        Buf w = Buf.writer();
        w.u8(0xAB).u16(0x1234).u32(42).u32l(0xFFFFFFFFL).i64(-1234567890123L).bytes(new byte[]{1, 2, 3});
        Buf r = Buf.reader(w.toByteArray());
        assertEquals(0xAB, r.u8());
        assertEquals(0x1234, r.u16());
        assertEquals(42, r.u32());
        assertEquals(0xFFFFFFFFL, r.u32l());
        assertEquals(-1234567890123L, r.i64());
        assertArrayEquals(new byte[]{1, 2, 3}, r.bytes(3));
        assertEquals(0, r.remaining());
    }

    @Test
    void varIntRoundTrip() {
        Buf w = Buf.writer();
        w.varInt(0).varInt(300).varInt(Integer.MAX_VALUE).varInt(-1).varInt(-2147483648);
        Buf r = Buf.reader(w.toByteArray());
        assertEquals(0, r.varInt());
        assertEquals(300, r.varInt());
        assertEquals(Integer.MAX_VALUE, r.varInt());
        assertEquals(-1, r.varInt());
        assertEquals(Integer.MIN_VALUE, r.varInt());
    }

    @Test
    void varLongRoundTrip() {
        Buf w = Buf.writer();
        w.varLong(0).varLong(1L << 40).varLong(Long.MAX_VALUE).varLong(-1);
        Buf r = Buf.reader(w.toByteArray());
        assertEquals(0L, r.varLong());
        assertEquals(1L << 40, r.varLong());
        assertEquals(Long.MAX_VALUE, r.varLong());
        assertEquals(-1L, r.varLong());
    }

    @Test
    void utfRoundTrip() {
        String s = "remote-offload ñ √ ☃ 漢字";
        Buf w = Buf.writer();
        w.utf(s);
        Buf r = Buf.reader(w.toByteArray());
        assertEquals(s, r.utf());
    }

    @Test
    void writerCannotRead() {
        assertThrows(IllegalStateException.class, () -> Buf.writer().u8());
    }

    @Test
    void utfTooLongThrows() {
        Buf r = Buf.reader(new byte[]{0, 0, 0, 5, 1});
        assertThrows(IllegalArgumentException.class, r::utf);
    }
}