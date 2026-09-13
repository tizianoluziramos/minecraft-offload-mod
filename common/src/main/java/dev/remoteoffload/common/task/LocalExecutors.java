package dev.remoteoffload.common.task;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registro de ejecutores. La MISMA instancia se usa para el fallback local
 * del mod y para los workers del servidor → resultados idénticos por
 * construcción (sin desincronización), y benchmarks local/remoto comparando
 * el mismo algoritmo.
 */
public final class LocalExecutors {

    private static final Map<TaskType, TaskHandler> HANDLERS = new EnumMap<>(TaskType.class);

    private LocalExecutors() {}

    public static void register(TaskHandler handler) {
        HANDLERS.put(handler.type(), handler);
    }

    public static TaskHandler handler(TaskType type) throws TaskException {
        TaskHandler h = HANDLERS.get(type);
        if (h == null) {
            throw new TaskException(TaskException.ERR_UNSUPPORTED, "no executor for " + type);
        }
        return h;
    }

    public static boolean has(TaskType type) {
        return HANDLERS.containsKey(type);
    }

    /** Ejecuta localmente (fallback del cliente o worker del servidor). */
    public static byte[] runLocal(TaskType type, byte[] request) throws TaskException {
        TaskHandler h = handler(type);
        h.validate(request);
        return h.run(request);
    }

    static {
        register(new CompressExecutor());
        register(new PathfindExecutor());
        register(new WorldQueryExecutor());
        register(new BenchmarkExecutor());
    }
}