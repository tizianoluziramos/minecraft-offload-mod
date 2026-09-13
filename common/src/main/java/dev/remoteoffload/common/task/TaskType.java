package dev.remoteoffload.common.task;

import java.util.HashMap;
import java.util.Map;

/**
 * Tipos de tarea del protocolo. IDs estables; añadir tipos nuevos no rompe
 * clientes viejos (reciben TASK_REJECT_UNSUPPORTED).
 */
public enum TaskType {
    COMPRESS(1),
    PATHFIND(2),
    WORLD_QUERY(3),
    BENCHMARK(4);

    private final int id;

    TaskType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    private static final Map<Integer, TaskType> BY_ID = new HashMap<>();

    static {
        for (TaskType t : values()) {
            BY_ID.put(t.id, t);
        }
    }

    public static TaskType fromId(int id) {
        TaskType t = BY_ID.get(id);
        if (t == null) {
            throw new IllegalArgumentException("unknown task type " + id);
        }
        return t;
    }
}