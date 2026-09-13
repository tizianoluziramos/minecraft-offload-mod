package dev.remoteoffload.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.remoteoffload.common.task.TaskType;
import dev.remoteoffload.runtime.Diagnostics;
import dev.remoteoffload.runtime.RemoteProcessingEngine;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Comandos /remote (estado, benchmark, pathfind, slime chunks, config). */
public final class RemoteProcessingCommands {

    private RemoteProcessingCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("remote")
                .then(literal("status").executes(c -> status(c)))
                .then(literal("report").executes(c -> report(c)))
                .then(literal("config").executes(c -> openConfig(c)))
                .then(literal("cancel").executes(c -> cancel(c)))
                .then(literal("bench")
                        .then(argument("size", IntegerArgumentType.integer(64, 1_000_000))
                                .executes(c -> bench(c, IntegerArgumentType.getInteger(c, "size"), 2000))))
                .then(literal("path")
                        .then(argument("range", IntegerArgumentType.integer(4, 64))
                                .executes(c -> path(c, IntegerArgumentType.getInteger(c, "range"))))
                        .executes(c -> path(c, 32)))
                .then(literal("slime")
                        .then(argument("cx", IntegerArgumentType.integer())
                                .then(argument("cz", IntegerArgumentType.integer())
                                        .executes(c -> slime(c, IntegerArgumentType.getInteger(c, "cx"),
                                                IntegerArgumentType.getInteger(c, "cz")))))));
    }

    private static int status(CommandContext<FabricClientCommandSource> c) {
        RemoteProcessingEngine e = RemoteProcessingEngine.instance();
        chat("RP state: " + (e == null ? "n/a" : e.state().name())
                + (e != null && e.isReady() ? "  server: " + e.serverInfo() : "")
                + "  RTT: " + (e != null ? (e.lastRttUs() / 1000.0) : 0) + "ms"
                + "  pend: " + (e != null ? e.pendingCount() : 0));
        return 1;
    }

    private static int report(CommandContext<FabricClientCommandSource> c) {
        RemoteProcessingEngine e = RemoteProcessingEngine.instance();
        if (e == null) {
            chat("Motor no inicializado");
            return 1;
        }
        chat(Diagnostics.report(e));
        return 1;
    }

    private static int openConfig(CommandContext<FabricClientCommandSource> c) {
        Minecraft.getInstance().setScreen(new RemoteProcessingConfigScreen(null));
        return 1;
    }

    private static int cancel(CommandContext<FabricClientCommandSource> c) {
        RemoteProcessingEngine e = RemoteProcessingEngine.instance();
        chat("Tareas en vuelo → fallback local: " + (e != null ? e.cancelAll() : 0));
        return 1;
    }

    private static int bench(CommandContext<FabricClientCommandSource> c, int size, int iters) {
        RemoteProcessingEngine e = RemoteProcessingEngine.instance();
        if (e == null) {
            chat("Motor no inicializado");
            return 1;
        }
        chat("Benchmark " + size + " (iters 2000) → ejecución offload/local...");
        RemoteTasks.bench(e, size, iters, 0x0F, 42).whenComplete((r, ex) -> Minecraft.getInstance().execute(() -> {
            if (ex != null) {
                chat("bench error: " + ex.getMessage());
            } else {
                chat("checksum=" + r.checksum() + "  time=" + (r.timeUs() / 1000.0) + "ms");
            }
        }));
        return 1;
    }

    private static int path(CommandContext<FabricClientCommandSource> c, int range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            chat("Debes estar en un mundo");
            return 1;
        }
        ClientLevel level = mc.level;
        BlockPos from = mc.player.blockPosition();
        Vec3 look = mc.player.getLookAngle();
        Vec3 eye = mc.player.getEyePosition().add(look.scale(range));
        BlockPos to = new BlockPos((int) eye.x, from.getY(), (int) eye.z);

        RemoteProcessingEngine e = RemoteProcessingEngine.instance();
        if (e == null) {
            chat("Motor no inicializado");
            return 1;
        }
        long t0 = System.nanoTime();
        WorldSnapshotter.Snapshot snap;
        try {
            snap = WorldSnapshotter.build(level, from, to, range);
        } catch (Exception ex) {
            chat("snapshot falló: " + ex.getMessage());
            return 1;
        }
        chat("PATH " + snap.w() + "x" + snap.h() + " grid, snapshot en "
                + ((System.nanoTime() - t0) / 1_000_000.0) + " ms → offload/local...");
        RemoteTasks.pathfind(e, snap.request()).whenComplete((r, ex) -> Minecraft.getInstance().execute(() -> {
            if (ex != null) {
                chat("path error: " + ex.getMessage());
                return;
            }
            if (r.found()) {
                PathState.update(snap, to);
                chat("Ruta: cost=" + r.cost() + " nodes=" + r.nodesExpanded()
                        + " len=" + r.pathGrid().size() + "  calc=" + (r.timeUs() / 1000.0) + "ms");
            } else {
                PathState.clear();
                chat("Sin ruta encontrada (" + r.nodesExpanded() + " nodos)");
            }
        }));
        return 1;
    }

    private static int slime(CommandContext<FabricClientCommandSource> c, int cx, int cz) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.level == null || server == null) {
            chat("Debes estar en un mundo singleplayer");
            return 1;
        }
        RemoteProcessingEngine e = RemoteProcessingEngine.instance();
        if (e == null) {
            chat("Motor no inicializado");
            return 1;
        }
        long seed = server.overworld().getSeed();
        RemoteTasks.slimeChunk(e, seed, cx, cz).whenComplete((r, ex) -> Minecraft.getInstance().execute(() -> {
            if (ex != null) {
                chat("slime error: " + ex.getMessage());
            } else {
                chat("Chunk (" + cx + "," + cz + ") es slime: " + r.isSlimeChunk()
                        + (r.note().isEmpty() ? "" : "  [" + r.note() + "]"));
            }
        }));
        return 1;
    }

    private static void chat(String s) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(s));
        }
    }
}