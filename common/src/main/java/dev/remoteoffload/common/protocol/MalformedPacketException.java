package dev.remoteoffload.common.protocol;

/**
 * Paquete malformado o fuera de las reglas de saneamiento.
 * Quien lo lanza es el lector de conexión; el servidor lo convierte en
 * infracción (cierre tras N).
 */
public class MalformedPacketException extends RuntimeException {

    public static final int ERR_CRC = 1;
    public static final int ERR_MAGIC = 2;
    public static final int ERR_VERSION = 3;
    public static final int ERR_TYPE = 4;
    public static final int ERR_FLAGS = 5;
    public static final int ERR_SIZE = 6;
    public static final int ERR_FRAGMENT = 7;

    private final int code;

    public MalformedPacketException(int code, String message) {
        super(message);
        this.code = code;
    }

    public MalformedPacketException(String message) {
        this(ERR_TYPE, message);
    }

    public int code() {
        return code;
    }
}