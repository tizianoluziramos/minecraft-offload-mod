package dev.remoteoffload.common.task;

import dev.remoteoffload.common.serial.Buf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Pathfinding A* sobre un grid plano de superficie (columnas).
 *
 * <p>Request:
 * u8 W, u8 H, bytes(W*H) celdas row-major (0=air, 1=ground(coste 10), 2=water(coste 30), 3=sólido),
 * u16 startIdx, u16 goalIdx, u8 allowDiag, u32 maxNodes, u64 budgetUs</p>
 *
 * <p>Response: u8 found (0 none, 1 full, 2 partial), u32 nodesExpanded, u32 pathLen,
 * u32 cost, u64 timeUs, por-punto u8 x, u8 y.</p>
 */
public final class PathfindExecutor implements TaskHandler {

    public static final int AIR = 0;
    public static final int GROUND = 1;
    public static final int WATER = 2;
    public static final int SOLID = 3;

    private static final int COST_GROUND = 10;
    private static final int COST_WATER = 30;
    private static final int COST_DIAG = 14;

    @Override
    public TaskType type() {
        return TaskType.PATHFIND;
    }

    @Override
    public boolean cacheable() {
        return false;
    }

    @Override
    public void validate(byte[] request) throws TaskException {
        Buf r = Buf.reader(request);
        if (r.remaining() < 4) {
            throw new TaskException(TaskException.ERR_MALFORMED, "pathfind request too short");
        }
        int w = r.u8();
        int h = r.u8();
        if (w < 1 || h < 1 || w > 128 || h > 128 || (long) w * h > 16384) {
            throw new TaskException(TaskException.ERR_MALFORMED, "grid too large: " + w + "x" + h);
        }
        if (r.remaining() < w * h + 2 + 2 + 1 + 4 + 8) {
            throw new TaskException(TaskException.ERR_MALFORMED, "pathfind request truncated");
        }
        r.bytes(w * h); // skip cells
        int start = r.u16();
        int goal = r.u16();
        if (start >= w * h || goal >= w * h) {
            throw new TaskException(TaskException.ERR_MALFORMED, "start/goal out of grid");
        }
    }

    @Override
    public byte[] run(byte[] request) throws TaskException {
        long t0 = System.nanoTime();
        Buf r = Buf.reader(request);
        int w = r.u8();
        int h = r.u8();
        byte[] cells = r.bytes(w * h);
        int start = r.u16();
        int goal = r.u16();
        int allowDiag = r.u8();
        int maxNodes = r.u32();
        long budgetNs = r.i64();

        if (allowDiag != 0 && allowDiag != 1) {
            throw new TaskException(TaskException.ERR_MALFORMED, "allowDiag must be 0/1");
        }
        if (maxNodes < 1 || maxNodes > 1_000_000) {
            throw new TaskException(TaskException.ERR_MALFORMED, "maxNodes out of range");
        }
        if (budgetNs < 0 || budgetNs > 60_000_000_000L) {
            throw new TaskException(TaskException.ERR_MALFORMED, "budget out of range");
        }

        Result res = astar(w, h, cells, start, goal, allowDiag == 1, maxNodes, budgetNs);
        long timeUs = (System.nanoTime() - t0) / 1000;

        Buf w2 = Buf.writer();
        w2.u8(res.found)
                .u32(res.nodesExpanded)
                .u32(res.path.size())
                .u32(res.cost)
                .u32l(timeUs);
        for (int[] p : res.path) {
            w2.u8(p[0]).u8(p[1]);
        }
        return w2.toByteArray();
    }

    private static final class Result {
        int found;
        int nodesExpanded;
        int cost;
        List<int[]> path = new ArrayList<>();
    }

    private static Result astar(int w, int h, byte[] cells, int start, int goal,
                                boolean diag, int maxNodes, long budgetNs) {
        Result res = new Result();
        int n = w * h;
        if (cells[start] == SOLID || cells[goal] == SOLID) {
            res.found = 0;
            return res;
        }

        int[] g = new int[n];
        int[] f = new int[n];
        int[] parent = new int[n];
        boolean[] closed = new boolean[n];
        java.util.Arrays.fill(g, Integer.MAX_VALUE);
        java.util.Arrays.fill(parent, -1);
        g[start] = 0;
        f[start] = heuristic(start, goal, w, diag);

        PriorityQueue<Integer> open = new PriorityQueue<>(
                (a, b) -> f[a] != f[b] ? Integer.compare(f[a], f[b]) : Integer.compare(a, b));
        open.add(start);
        res.nodesExpanded = 0;

        long deadline = budgetNs > 0 ? System.nanoTime() + budgetNs : Long.MAX_VALUE;
        int[] dx = diag
                ? new int[]{1, -1, 0, 0, 1, 1, -1, -1}
                : new int[]{1, -1, 0, 0};
        int[] dy = diag
                ? new int[]{0, 0, 1, -1, 1, -1, 1, -1}
                : new int[]{0, 0, 1, -1};

        while (!open.isEmpty()) {
            if (res.nodesExpanded >= maxNodes) {
                res.found = 2; // partial
                buildPath(parent, goal, w, res);
                return res;
            }
            if (System.nanoTime() > deadline) {
                res.found = 2;
                buildPath(parent, goal, w, res);
                return res;
            }
            int cur = open.poll();
            if (closed[cur]) {
                continue;
            }
            closed[cur] = true;
            res.nodesExpanded++;
            if (cur == goal) {
                res.found = 1;
                res.cost = g[cur];
                buildPath(parent, goal, w, res);
                return res;
            }
            int cx = cur % w;
            int cy = cur / w;
            for (int d = 0; d < dx.length; d++) {
                int nx = cx + dx[d];
                int ny = cy + dy[d];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                    continue;
                }
                int ni = ny * w + nx;
                if (closed[ni] || cells[ni] == SOLID) {
                    continue;
                }
                boolean step = nx == cx || ny == cy;
                int moveCost = step ? moveCost(cells[ni]) : COST_DIAG + moveCost(cells[ni]);
                int tentative = g[cur] + moveCost;
                if (tentative < g[ni]) {
                    g[ni] = tentative;
                    parent[ni] = cur;
                    f[ni] = tentative + heuristic(ni, goal, w, diag);
                    open.add(ni);
                }
            }
        }
        res.found = 0;
        return res;
    }

    private static int moveCost(byte cell) {
        return switch (cell) {
            case GROUND -> COST_GROUND;
            case WATER -> COST_WATER;
            default -> Integer.MAX_VALUE / 4; // air → caro pero no prohibido
        };
    }

    private static int heuristic(int a, int goal, int w, boolean diag) {
        int ax = a % w;
        int ay = a / w;
        int gx = goal % w;
        int gy = goal / w;
        int dx = Math.abs(ax - gx);
        int dy = Math.abs(ay - gy);
        return diag
                ? COST_GROUND * (dx + dy) + (COST_DIAG - 2 * COST_GROUND) * Math.min(dx, dy)
                : COST_GROUND * (dx + dy);
    }

    private static void buildPath(int[] parent, int goal, int w, Result res) {
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        int cur = goal;
        while (cur != -1) {
            stack.addFirst(cur);
            cur = parent[cur];
            if (stack.size() > 4096) {
                break;
            }
        }
        for (int i : stack) {
            res.path.add(new int[]{i % w, i / w});
        }
        if (res.path.isEmpty()) {
            res.found = 0;
        }
    }
}