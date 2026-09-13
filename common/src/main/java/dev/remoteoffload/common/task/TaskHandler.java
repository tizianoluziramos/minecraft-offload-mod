package dev.remoteoffload.common.task;

/**
 * Ejecutor real de una tarea. La MÍSMA implementación se usa en el servidor
 * y en el fallback local del cliente → resultados idénticos (cero desincre).
 */
public interface TaskHandler {

    TaskType type();

    /** Valida estrictamente el payload ANTES de ejecutar (saneamiento). */
    void validate(byte[] request) throws TaskException;

    /** Ejecuta la tarea y devuelve el payload de respuesta. */
    byte[] run(byte[] request) throws TaskException;

    /** Si el resultado es cacheable por contenido canónico. */
    boolean cacheable();

    /** Clave de caché (por defecto hash del request; mayúsculas para signatures). */
    default byte[] cacheKey(byte[] request) {
        return dev.remoteoffload.common.util.Hash.sha256(request);
    }
}