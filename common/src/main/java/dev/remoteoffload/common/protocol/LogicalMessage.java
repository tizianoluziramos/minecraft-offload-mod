package dev.remoteoffload.common.protocol;

/**
 * Mensaje lógico recibido (tras framear/reensamble/descompresión).
 */
public final class LogicalMessage {

    public final int type;
    public final int flags;
    public final long taskId;
    public final byte[] payload;

    public LogicalMessage(int type, int flags, long taskId, byte[] payload) {
        this.type = type;
        this.flags = flags;
        this.taskId = taskId;
        this.payload = payload;
    }

    public boolean compressed() {
        return (flags & PacketFlags.COMPRESSED) != 0;
    }
}