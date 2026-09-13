package dev.remoteoffload.common.task;

import dev.remoteoffload.common.serial.Buf;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Tarea de diagnóstico/benchmark: ejecuta kernels CPU idénticos en local y en
 * remoto y devuelve checksum + tiempos por kernel. Sirve para medir el
 * ahorro efectivo y validar la igualdad funcional (checksum local == remoto).
 *
 * <p>Request: u32 payloadSize, u16 iterations, u8 kernelMask
 * (bit0=fib, bit1=primes, bit2=sha256, bit3=sort), u8 seed.</p>
 * <p>Response: u32 checksum, u64 timeUs, u64 fibUs, u64 primesUs, u64 shaUs, u64 sortUs.</p>
 */
public final class BenchmarkExecutor implements TaskHandler {

    @Override
    public TaskType type() {
        return TaskType.BENCHMARK;
    }

    @Override
    public boolean cacheable() {
        return false;
    }

    @Override
    public void validate(byte[] request) throws TaskException {
        Buf r = Buf.reader(request);
        if (r.remaining() != 4 + 2 + 1 + 1) {
            throw new TaskException(TaskException.ERR_MALFORMED, "benchmark request size");
        }
        int size = r.u32();
        int iters = r.u16();
        int mask = r.u8();
        if (size < 16 || size > 8 * 1024 * 1024 || iters < 1 || iters > 1000
                || mask == 0 || (mask & ~0x0F) != 0) {
            throw new TaskException(TaskException.ERR_MALFORMED, "benchmark params out of range");
        }
    }

    @Override
    public byte[] run(byte[] request) throws TaskException {
        Buf r = Buf.reader(request);
        int size = r.u32();
        int iters = r.u16();
        int mask = r.u8();
        int seed = r.u8() & 0xFF;

        SplittableRandom sr = new SplittableRandom(seed);
        byte[] data = new byte[size];
        sr.nextBytes(data);

        long t0 = System.nanoTime();
        long fibUs = 0;
        long primesUs = 0;
        long shaUs = 0;
        long sortUs = 0;

        long checksum = 1;
        if ((mask & 0x01) != 0) {
            long s = System.nanoTime();
            checksum ^= fibLoop(iters * 8);
            fibUs = (System.nanoTime() - s) / 1000;
        }
        if ((mask & 0x02) != 0) {
            long s = System.nanoTime();
            checksum ^= primesLoop((size / 1024) + 1);
            primesUs = (System.nanoTime() - s) / 1000;
        }
        if ((mask & 0x04) != 0) {
            long s = System.nanoTime();
            checksum ^= shaLoop(data, iters);
            shaUs = (System.nanoTime() - s) / 1000;
        }
        if ((mask & 0x08) != 0) {
            long s = System.nanoTime();
            checksum ^= sortLoop(size, seed);
            sortUs = (System.nanoTime() - s) / 1000;
        }
        long timeUs = (System.nanoTime() - t0) / 1000;

        Buf w = Buf.writer();
        w.u32((int) (checksum & 0xFFFFFFFFL))
                .u32l(timeUs)
                .u32l(fibUs)
                .u32l(primesUs)
                .u32l(shaUs)
                .u32l(sortUs);
        return w.toByteArray();
    }

    private static long fibLoop(int iters) {
        long acc = 0;
        for (int i = 0; i < iters; i++) {
            long a = 0, b = 1;
            for (int j = 0; j < 24; j++) {
                long c = a + b;
                a = b;
                b = c;
            }
            acc ^= b;
        }
        return acc;
    }

    private static long primesLoop(int limit) {
        long acc = 0;
        for (int n = 2; n < limit; n++) {
            boolean prime = true;
            for (int d = 2; d * d <= n; d++) {
                if (n % d == 0) {
                    prime = false;
                    break;
                }
            }
            if (prime) {
                acc ^= n;
            }
        }
        return acc;
    }

    private static long shaLoop(byte[] data, int iters) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = data;
            for (int i = 0; i < iters; i++) {
                h = md.digest(h);
            }
            long acc = 0;
            for (int i = 0; i < 8 && i < h.length; i++) {
                acc = (acc << 8) | (h[i] & 0xFF);
            }
            return acc;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long sortLoop(int size, int seed) {
        SplittableRandom r2 = new SplittableRandom(seed ^ 0xABCDEF);
        int[] arr = new int[Math.min(size, 1 << 20)];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = r2.nextInt();
        }
        Arrays.sort(arr);
        long acc = 0;
        for (int i = 0; i < arr.length; i += Math.max(1, arr.length / 16)) {
            acc ^= arr[i];
        }
        return acc;
    }
}