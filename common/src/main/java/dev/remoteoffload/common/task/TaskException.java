package dev.remoteoffload.common.task;

/** Error de ejecución de una tarea (propagado al cliente como TASK_REJECT). */
public class TaskException extends Exception {

    public static final int ERR_MALFORMED = 1;
    public static final int ERR_UNSUPPORTED = 2;
    public static final int ERR_BUSY = 3;
    public static final int ERR_TIMEOUT = 4;
    public static final int ERR_INTERNAL = 5;
    public static final int ERR_TOO_LARGE = 6;

    private final int code;

    public TaskException(int code, String message) {
        super(message);
        this.code = code;
    }

    public TaskException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int code() {
        return code;
    }
}