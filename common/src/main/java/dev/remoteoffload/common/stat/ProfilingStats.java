package dev.remoteoffload.common.stat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Métricas de perfilado del agente, por tipo de tarea. Hilo-safe.
 * Expuestas en el HUD y en el reporte de diagnóstico.
 */
public final class ProfilingStats {

    public static final class PerType {
        public final LongAdder submits = new LongAdder();
        public final LongAdder local = new LongAdder();
        public final LongAdder remote = new LongAdder();
        public final LongAdder cacheHits = new LongAdder();
        public final LongAdder errors = new LongAdder();
        public final LongAdder localTotalUs = new LongAdder();
        public final LongAdder remoteTotalUs = new LongAdder();
        public final LongAdder serUs = new LongAdder();
        public final LongAdder deserUs = new LongAdder();
        public final LongAdder bytesTx = new LongAdder();
        public final LongAdder bytesRx = new LongAdder();
        public final DoubleAdder rttMeanUs = new DoubleAdder();

        public long avgLocalUs() {
            long n = local.sum();
            return n == 0 ? 0 : localTotalUs.sum() / n;
        }

        public long avgRemoteUs() {
            long n = remote.sum();
            return n == 0 ? 0 : remoteTotalUs.sum() / n;
        }
    }

    private final Map<String, PerType> perType = new ConcurrentHashMap<>();
    private final LongAdder totalBytesTx = new LongAdder();
    private final LongAdder totalBytesRx = new LongAdder();
    private volatile long lastRttUs = -1;
    private volatile double rttEmaUs;

    public PerType forType(String name) {
        return perType.computeIfAbsent(name, k -> new PerType());
    }

    public Map<String, PerType> snapshot() {
        return Map.copyOf(perType);
    }

    public void addTx(long bytes) {
        totalBytesTx.add(bytes);
    }

    public void addRx(long bytes) {
        totalBytesRx.add(bytes);
    }

    public long totalBytesTx() {
        return totalBytesTx.sum();
    }

    public long totalBytesRx() {
        return totalBytesRx.sum();
    }

    public void recordRtt(long rttUs) {
        lastRttUs = rttUs;
        rttEmaUs = rttEmaUs == 0 ? rttUs : rttEmaUs * 0.8 + rttUs * 0.2;
    }

    public long lastRttUs() {
        return lastRttUs;
    }

    public long rttEmaUs() {
        return (long) rttEmaUs;
    }
}