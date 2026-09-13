package dev.remoteoffload.common.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Tipos de paquete del protocolo MKOF. Los IDs son estables y no deben
 * reutilizarse; el 0 no es un tipo válido.
 */
public enum PacketType {
    CLIENT_HELLO(0x01),
    SERVER_WELCOME(0x02),
    AUTH_CHALLENGE(0x03),
    AUTH_RESPONSE(0x04),
    AUTH_RESULT(0x05),

    TASK_SUBMIT(0x10),
    TASK_ACCEPT(0x11),
    TASK_REJECT(0x12),
    TASK_RESULT(0x13),
    TASK_CANCEL(0x14),
    TASK_PROGRESS(0x15),
    TASK_LIST(0x1F),

    PING(0x20),
    PONG(0x21),

    ERROR(0x30),
    BYE(0x31);

    private final int id;

    PacketType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    private static final Map<Integer, PacketType> BY_ID = new HashMap<>();

    static {
        for (PacketType t : values()) {
            BY_ID.put(t.id, t);
        }
    }

    public static PacketType fromId(int id) throws MalformedPacketException {
        PacketType t = BY_ID.get(id);
        if (t == null) {
            throw new MalformedPacketException("unknown packet type 0x" + Integer.toHexString(id));
        }
        return t;
    }
}