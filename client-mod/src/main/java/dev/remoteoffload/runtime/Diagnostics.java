package dev.remoteoffload.runtime;

import dev.remoteoffload.common.stat.ProfilingStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reporte de diagnóstico (entregable de perfilado del proyecto). */
public final class Diagnostics {

    private Diagnostics() {}

    public static String report(RemoteProcessingEngine engine) {
        ProfilingStats s = engine.stats();
        StringBuilder sb = new StringBuilder();
        sb.append("--- Remote Offload diagnostics ---\n");
        sb.append("state    : ").append(engine.state())
                .append(engine.isReady() ? "  server='" + engine.serverInfo() + "'" : "")
                .append("\n");
        sb.append("last err : ").append(engine.lastError()).append("\n");
        sb.append("RTT (EMA): ").append(engine.lastRttUs() / 1000.0f).append(" ms\n");
        sb.append("bytes tx : ").append(s.totalBytesTx()).append("  rx: ").append(s.totalBytesRx()).append("\n");

        Map<String, ProfilingStats.PerType> m = s.snapshot();
        if (m.isEmpty()) {
            sb.append("no tasks yet\n");
            return sb.toString();
        }
        sb.append(String.format("%-10s %8s %8s %9s %9s %7s %7s%n",
                "task", "local", "remote", "locAvgMs", "remAvgMs", "hits", "errs"));
        for (Map.Entry<String, ProfilingStats.PerType> e : m.entrySet()) {
            ProfilingStats.PerType p = e.getValue();
            long locAvg = p.avgLocalUs() / 1000;
            long remAvg = p.avgRemoteUs() / 1000;
            sb.append(String.format("%-10s %8d %8d %9d %9d %7d %7d%n",
                    e.getKey(), p.local.sum(), p.remote.sum(), locAvg, remAvg,
                    p.cacheHits.sum(), p.errors.sum()));
        }
        long localTotalUs = m.values().stream().mapToLong(p -> p.localTotalUs.sum()).sum();
        long remoteTotalUs = m.values().stream().mapToLong(p -> p.remoteTotalUs.sum()).sum();
        long remoteCount = m.values().stream().mapToLong(p -> p.remote.sum()).sum();
        sb.append("\nCPU local total: ").append(ms(localTotalUs))
                .append("  | CPU remota total: ").append(ms(remoteTotalUs))
                .append("  | tareas remotas: ").append(remoteCount)
                .append("  → ahorro cpu-local estimado ≈ ")
                .append(ms(Math.max(0, localTotalUs - remoteTotalUs))).append("\n");
        return sb.toString();
    }

    private static String ms(long us) {
        return String.format("%.3f ms", us / 1000.0);
    }

    /** Resumen de una línea para HUD. */
    public static List<String> hudLines(RemoteProcessingEngine engine) {
        List<String> out = new ArrayList<>();
        out.add("RP: " + engine.state() + (engine.isReady() ? " @" + (engine.lastRttUs() / 1000.0) + "ms" : ""));
        if (engine.lastError() != null && !engine.lastError().isEmpty() && !engine.isReady()) {
            out.add("  " + engine.lastError());
        }
        int pending = engine.pendingCount();
        if (pending > 0) {
            out.add("  tasks: " + pending);
        }
        return out;
    }
}