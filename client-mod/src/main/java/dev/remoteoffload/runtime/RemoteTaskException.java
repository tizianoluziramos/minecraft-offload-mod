package dev.remoteoffload.runtime;

/** Error de tarea remota (propagado como completo-así-fallback). */
public final class RemoteTaskException extends RuntimeException {
    private final int code;

    public RemoteTaskException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}