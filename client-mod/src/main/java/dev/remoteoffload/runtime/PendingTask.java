package dev.remoteoffload.runtime;

import dev.remoteoffload.common.task.TaskType;

import java.util.concurrent.CompletableFuture;

/** Tarea en espera de resultado remoto. */
final class PendingTask {

    final long taskId;
    final TaskType type;
    final byte[] request;
    final CompletableFuture<byte[]> future = new CompletableFuture<>();
    volatile long deadlineMs;
    volatile long submittedAtMs;
    volatile boolean remotePath;

    PendingTask(long taskId, TaskType type, byte[] request) {
        this.taskId = taskId;
        this.type = type;
        this.request = request;
    }
}