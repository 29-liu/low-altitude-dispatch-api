import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DispatchApiServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 与原项目保持一致：1秒一个时间步
    private static final long STEP_MS = 1000L;

    // 与原项目保持一致：5秒冲突时间裕度
    private static final long CONFLICT_MARGIN_MS = 5000L;

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8088")
        );

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port),
                0
        );

        server.createContext("/api/health", DispatchApiServer::health);
        server.createContext("/api/dispatch/plan", DispatchApiServer::dispatchPlan);

        server.setExecutor(null);
        server.start();

        System.out.println("========================================");
        System.out.println("Low Altitude Dispatch API started");
        System.out.println("Version: 3.0");
        System.out.println("Port: " + port);
        System.out.println("GET  /api/health");
        System.out.println("POST /api/dispatch/plan");
        System.out.println("Algorithm: Space-Time A*");
        System.out.println("========================================");
    }

    private static void health(HttpExchange exchange) throws IOException {

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405,
                    "{\"success\":false,\"message\":\"Method Not Allowed\"}");
            return;
        }

        ObjectNode result = MAPPER.createObjectNode();

        result.put("success", true);
        result.put("service", "Low Altitude Dispatch API");
        result.put("status", "running");
        result.put("version", "3.0");
        result.put("algorithmConnected", true);
        result.put("algorithm", "Space-Time A*");

        send(exchange, 200, MAPPER.writeValueAsString(result));
    }

    private static void dispatchPlan(HttpExchange exchange) throws IOException {

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405,
                    "{\"success\":false,\"message\":\"POST required\"}");
            return;
        }

        try {

            String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            System.out.println("========== Dispatch Request ==========");
            System.out.println(body);
            System.out.println("======================================");

            JsonNode root = MAPPER.readTree(body);

            // 女娲传入的是 request_json 字符串时，再解析一层
            if (root.has("request_json")
                    && root.get("request_json").isTextual()
                    && !root.get("request_json").asText().isBlank()) {

                root = MAPPER.readTree(
                        root.get("request_json").asText()
                );
            }

            JsonNode pickupNode = root.get("pickup");
            JsonNode deliveryNode = root.get("delivery");
            JsonNode dronesNode = root.get("drones");
            JsonNode flightPlansNode = root.get("flightPlans");

            double cargoWeight =
                    root.path("cargoWeight").asDouble(0);

            double deadlineMin =
                    root.path("deadlineMin").asDouble(0);

            if (pickupNode == null || deliveryNode == null) {
                sendError(exchange, 400,
                        "缺少取货点或送达点坐标");
                return;
            }

            if (dronesNode == null || !dronesNode.isArray()) {
                sendError(exchange, 400,
                        "缺少无人机状态数据");
                return;
            }

            int pickupX = pickupNode.path("x").asInt();
            int pickupY = pickupNode.path("y").asInt();

            int deliveryX = deliveryNode.path("x").asInt();
            int deliveryY = deliveryNode.path("y").asInt();

            GridPoint pickup =
                    new GridPoint(pickupX, pickupY);

            GridPoint delivery =
                    new GridPoint(deliveryX, deliveryY);

            // ====================================================
            // 1. 基础无人机筛选
            // ====================================================

            List<Candidate> candidates = new ArrayList<>();
            ArrayNode excluded = MAPPER.createArrayNode();

            for (JsonNode drone : dronesNode) {

                String droneId =
                        drone.path("droneId").asText("");

                String status =
                        drone.path("status").asText("");

                double battery =
                        drone.path("battery").asDouble(0);

                double payloadCapacity =
                        drone.path("payloadCapacity").asDouble(0);

                double currentPayload =
                        drone.path("currentPayload").asDouble(0);

                int x =
                        drone.path("x").asInt();

                int y =
                        drone.path("y").asInt();

                String reason = null;

                if (!"可用".equals(status)) {

                    reason = "状态不是可用";

                } else if (battery < 20.0) {

                    reason = "电量低于20%";

                } else if (
                        payloadCapacity - currentPayload
                                < cargoWeight
                ) {

                    reason = "剩余载荷能力不足";
                }

                if (reason != null) {

                    ObjectNode item =
                            MAPPER.createObjectNode();

                    item.put("droneId", droneId);
                    item.put("reason", reason);

                    excluded.add(item);
                    continue;
                }

                double distanceToPickup =
                        Math.hypot(
                                x - pickupX,
                                y - pickupY
                        );

                candidates.add(
                        new Candidate(
                                droneId,
                                battery,
                                x,
                                y,
                                distanceToPickup
                        )
                );
            }

            if (candidates.isEmpty()) {

                ObjectNode result =
                        MAPPER.createObjectNode();

                result.put("success", false);
                result.put("algorithmConnected", true);
                result.put(
                        "algorithmStage",
                        "spatiotemporal-planning"
                );

                result.put(
                        "message",
                        "当前没有满足条件的可用无人机"
                );

                result.set(
                        "excludedDrones",
                        excluded
                );

                send(
                        exchange,
                        200,
                        MAPPER.writeValueAsString(result)
                );

                return;
            }

            // 距离优先；距离相同时电量高者优先
            candidates.sort(
                    Comparator
                            .comparingDouble(
                                    (Candidate c) ->
                                            c.distanceToPickup
                            )
                            .thenComparing(
                                    Comparator
                                            .comparingDouble(
                                                    (Candidate c) ->
                                                            c.battery
                                            )
                                            .reversed()
                            )
            );

            Candidate selected =
                    candidates.get(0);

            GridPoint start =
                    new GridPoint(
                            selected.x,
                            selected.y
                    );

            // ====================================================
            // 2. 将现有飞行计划转换为 occupiedCells + TimeWindow
            // ====================================================

            Map<String, List<TimeWindow>>
                    occupiedCells =
                    buildOccupiedCells(
                            flightPlansNode,
                            selected.droneId
                    );

            // ====================================================
            // 3. 动态计算网格范围
            // 原程序 GRID_MAX=60，
            // 当前测试终点已经达到 (70,65)，因此改为动态范围
            // ====================================================

            int gridMax =
                    calculateGridMax(
                            root,
                            start,
                            pickup,
                            delivery
                    );

            // ====================================================
            // 4. 先计算未避让的基准路径冲突
            // ====================================================

            List<TimedPoint> baselinePath =
                    buildBaselinePath(
                            start,
                            pickup,
                            delivery,
                            STEP_MS
                    );

            int baselineConflictCount =
                    countPathConflicts(
                            baselinePath,
                            occupiedCells
                    );

            // ====================================================
            // 5. 第一段：无人机当前位置 → 取货点
            // ====================================================

            SpaceTimeAStar plannerToPickup =
                    new SpaceTimeAStar(
                            gridMax,
                            STEP_MS,
                            CONFLICT_MARGIN_MS,
                            occupiedCells
                    );

            SegmentResult toPickup =
                    plannerToPickup.plan(
                            start,
                            pickup,
                            0L
                    );

            if (!toPickup.success) {

                sendError(
                        exchange,
                        200,
                        "无人机到取货点的时空A*路径规划失败"
                );

                return;
            }

            // ====================================================
            // 6. 第二段：取货点 → 送达点
            // ====================================================

            SpaceTimeAStar plannerToDelivery =
                    new SpaceTimeAStar(
                            gridMax,
                            STEP_MS,
                            CONFLICT_MARGIN_MS,
                            occupiedCells
                    );

            SegmentResult toDelivery =
                    plannerToDelivery.plan(
                            pickup,
                            delivery,
                            toPickup.endTimeMs
                    );

            if (!toDelivery.success) {

                sendError(
                        exchange,
                        200,
                        "取货点到送达点的时空A*路径规划失败"
                );

                return;
            }

            // 合并两段轨迹
            List<TimedPoint> fullPath =
                    new ArrayList<>(
                            toPickup.path
                    );

            if (toDelivery.path.size() > 1) {
                fullPath.addAll(
                        toDelivery.path.subList(
                                1,
                                toDelivery.path.size()
                        )
                );
            }

            // ====================================================
            // 7. 再次验证避让后的轨迹是否仍存在冲突
            // ====================================================

            int remainingConflictCount =
                    countPathConflicts(
                            fullPath,
                            occupiedCells
                    );

            long endTimeMs =
                    toDelivery.endTimeMs;

            double estimatedTimeMin =
                    endTimeMs / 60000.0;

            boolean deadlineSatisfied =
                    deadlineMin <= 0
                            || estimatedTimeMin
                            <= deadlineMin;

            int avoidanceCount =
                    toPickup.adjustmentCount
                            + toDelivery.adjustmentCount;

            long avoidanceDelayMs =
                    toPickup.delayMs
                            + toDelivery.delayMs;

            boolean conflictDetected =
                    baselineConflictCount > 0;

            boolean conflictResolved =
                    conflictDetected
                            && remainingConflictCount == 0;

            // ====================================================
            // 8. 输出规划结果
            // ====================================================

            ObjectNode result =
                    MAPPER.createObjectNode();

            result.put("success", true);
            result.put(
                    "algorithmConnected",
                    true
            );

            result.put(
                    "algorithmStage",
                    "spatiotemporal-planning"
            );

            result.put(
                    "algorithm",
                    "Space-Time A*"
            );

            result.put(
                    "assignedDrone",
                    selected.droneId
            );

            result.put(
                    "battery",
                    selected.battery
            );

            result.put(
                    "distanceToPickup",
                    round(
                            selected.distanceToPickup
                    )
            );

            result.put(
                    "cargoWeight",
                    cargoWeight
            );

            result.put(
                    "deadlineMin",
                    deadlineMin
            );

            result.put(
                    "gridMax",
                    gridMax
            );

            result.put(
                    "stepMs",
                    STEP_MS
            );

            result.put(
                    "conflictMarginMs",
                    CONFLICT_MARGIN_MS
            );

            result.put(
                    "baselineConflictCount",
                    baselineConflictCount
            );

            result.put(
                    "conflictDetected",
                    conflictDetected
            );

            result.put(
                    "avoidanceAdjustmentCount",
                    avoidanceCount
            );

            result.put(
                    "avoidanceDelayMs",
                    avoidanceDelayMs
            );

            result.put(
                    "remainingConflictCount",
                    remainingConflictCount
            );

            result.put(
                    "conflictResolved",
                    conflictResolved
            );

            result.put(
                    "resolution",
                    conflictDetected
                            ? "SPACE_TIME_ASTAR_AVOIDANCE"
                            : "NONE"
            );

            result.put(
                    "pathPointCount",
                    fullPath.size()
            );

            result.put(
                    "estimatedTimeMin",
                    round(estimatedTimeMin)
            );

            result.put(
                    "deadlineSatisfied",
                    deadlineSatisfied
            );

            result.put(
                    "activeFlightPlanCount",
                    flightPlansNode != null
                            && flightPlansNode.isArray()
                            ? flightPlansNode.size()
                            : 0
            );

            if (conflictDetected) {

                result.put(
                        "message",
                        "检测到基准航迹存在时空冲突，已使用时空A*完成避让规划"
                );

            } else {

                result.put(
                        "message",
                        "未检测到基准航迹时空冲突，已完成时空A*路径规划"
                );
            }

            result.set(
                    "excludedDrones",
                    excluded
            );

            ArrayNode pathArray =
                    MAPPER.createArrayNode();

            for (TimedPoint point : fullPath) {

                ObjectNode p =
                        MAPPER.createObjectNode();

                p.put("x", point.x);
                p.put("y", point.y);
                p.put("tMs", point.tMs);

                pathArray.add(p);
            }

            result.set(
                    "path",
                    pathArray
            );

            send(
                    exchange,
                    200,
                    MAPPER.writeValueAsString(result)
            );

        } catch (Exception e) {

            e.printStackTrace();

            ObjectNode error =
                    MAPPER.createObjectNode();

            error.put("success", false);
            error.put(
                    "algorithmConnected",
                    true
            );

            error.put(
                    "message",
                    "算法请求解析失败：" + e.getMessage()
            );

            send(
                    exchange,
                    500,
                    MAPPER.writeValueAsString(error)
            );
        }
    }

    // ============================================================
    // 将已有飞行计划转换为时间窗占用
    // ============================================================

    private static Map<String, List<TimeWindow>>
    buildOccupiedCells(
            JsonNode plans,
            String selectedDrone
    ) {

        Map<String, List<TimeWindow>>
                occupied = new HashMap<>();

        if (plans == null || !plans.isArray()) {
            return occupied;
        }

        for (JsonNode plan : plans) {

            String droneId =
                    plan.path("droneId")
                            .asText("");

            // 不与当前被选中的无人机自身冲突
            if (selectedDrone.equals(droneId)) {
                continue;
            }

            String status =
                    plan.path("status")
                            .asText("");

            if (!"执行中".equals(status)
                    && !"已分配".equals(status)) {
                continue;
            }

            long startTimeMs =
                    plan.path("startTimeMs")
                            .asLong(0);

            long stepMs =
                    Math.max(
                            1,
                            plan.path("stepMs")
                                    .asLong(STEP_MS)
                    );

            JsonNode path =
                    plan.get("path");

            if (path == null || !path.isArray()) {
                continue;
            }

            for (int i = 0; i < path.size(); i++) {

                JsonNode point =
                        path.get(i);

                int x =
                        point.path("x").asInt();

                int y =
                        point.path("y").asInt();

                long t;

                if (point.has("tMs")) {

                    t = point.path("tMs")
                            .asLong();

                } else {

                    t = startTimeMs
                            + i * stepMs;
                }

                String key =
                        x + "," + y;

                occupied
                        .computeIfAbsent(
                                key,
                                k -> new ArrayList<>()
                        )
                        .add(
                                new TimeWindow(
                                        t,
                                        t + stepMs,
                                        droneId
                                )
                        );
            }
        }

        return occupied;
    }

    // ============================================================
    // 动态网格尺寸
    // ============================================================

    private static int calculateGridMax(
            JsonNode root,
            GridPoint start,
            GridPoint pickup,
            GridPoint delivery
    ) {

        int max =
                Math.max(
                        Math.max(
                                start.x,
                                start.y
                        ),
                        Math.max(
                                Math.max(
                                        pickup.x,
                                        pickup.y
                                ),
                                Math.max(
                                        delivery.x,
                                        delivery.y
                                )
                        )
                );

        JsonNode drones =
                root.get("drones");

        if (drones != null && drones.isArray()) {

            for (JsonNode drone : drones) {

                max = Math.max(
                        max,
                        Math.max(
                                drone.path("x").asInt(),
                                drone.path("y").asInt()
                        )
                );
            }
        }

        JsonNode plans =
                root.get("flightPlans");

        if (plans != null && plans.isArray()) {

            for (JsonNode plan : plans) {

                JsonNode path =
                        plan.get("path");

                if (path == null
                        || !path.isArray()) {
                    continue;
                }

                for (JsonNode p : path) {

                    max = Math.max(
                            max,
                            Math.max(
                                    p.path("x").asInt(),
                                    p.path("y").asInt()
                            )
                    );
                }
            }
        }

        // 至少100×100，并保留10格安全边界
        return Math.max(
                100,
                max + 10
        );
    }

    // ============================================================
    // 构造未避让的曼哈顿基准路径，用于检测原始冲突
    // ============================================================

    private static List<TimedPoint>
    buildBaselinePath(
            GridPoint start,
            GridPoint pickup,
            GridPoint delivery,
            long stepMs
    ) {

        List<TimedPoint> path =
                new ArrayList<>();

        long time = 0;

        int x = start.x;
        int y = start.y;

        path.add(
                new TimedPoint(
                        x,
                        y,
                        time
                )
        );

        while (x != pickup.x) {

            x += Integer.compare(
                    pickup.x,
                    x
            );

            time += stepMs;

            path.add(
                    new TimedPoint(
                            x,
                            y,
                            time
                    )
            );
        }

        while (y != pickup.y) {

            y += Integer.compare(
                    pickup.y,
                    y
            );

            time += stepMs;

            path.add(
                    new TimedPoint(
                            x,
                            y,
                            time
                    )
            );
        }

        while (x != delivery.x) {

            x += Integer.compare(
                    delivery.x,
                    x
            );

            time += stepMs;

            path.add(
                    new TimedPoint(
                            x,
                            y,
                            time
                    )
            );
        }

        while (y != delivery.y) {

            y += Integer.compare(
                    delivery.y,
                    y
            );

            time += stepMs;

            path.add(
                    new TimedPoint(
                            x,
                            y,
                            time
                    )
            );
        }

        return path;
    }

    // ============================================================
    // 验证一条带时间戳轨迹是否与已有时间窗冲突
    // ============================================================

    private static int countPathConflicts(
            List<TimedPoint> path,
            Map<String, List<TimeWindow>> occupied
    ) {

        int conflicts = 0;

        for (TimedPoint point : path) {

            String key =
                    point.x + "," + point.y;

            List<TimeWindow> windows =
                    occupied.getOrDefault(
                            key,
                            Collections.emptyList()
                    );

            for (TimeWindow tw : windows) {

                if (tw.overlaps(
                        point.tMs,
                        point.tMs + STEP_MS,
                        CONFLICT_MARGIN_MS
                )) {

                    conflicts++;
                }
            }
        }

        return conflicts;
    }

    // ============================================================
    // 时空 A*
    // ============================================================

    static class SpaceTimeAStar {

        final int gridMax;
        final long stepMs;
        final long conflictMarginMs;

        final Map<String, List<TimeWindow>>
                occupiedCells;

        SpaceTimeAStar(
                int gridMax,
                long stepMs,
                long conflictMarginMs,
                Map<String, List<TimeWindow>>
                        occupiedCells
        ) {

            this.gridMax =
                    gridMax;

            this.stepMs =
                    stepMs;

            this.conflictMarginMs =
                    conflictMarginMs;

            this.occupiedCells =
                    occupiedCells;
        }

        SegmentResult plan(
                GridPoint start,
                GridPoint goal,
                long startTimeMs
        ) {

            PriorityQueue<NodeTime> open =
                    new PriorityQueue<>(
                            Comparator
                                    .comparingDouble(
                                            n -> n.f
                                    )
                    );

            Map<String, Long> bestArrival =
                    new HashMap<>();

            NodeTime startNode =
                    new NodeTime(
                            start.x,
                            start.y,
                            0,
                            heuristic(
                                    start.x,
                                    start.y,
                                    goal.x,
                                    goal.y
                            ),
                            startTimeMs,
                            null,
                            0,
                            0
                    );

            open.add(startNode);

            bestArrival.put(
                    start.key(),
                    startTimeMs
            );

            int[][] dirs = {
                    {1, 0},
                    {-1, 0},
                    {0, 1},
                    {0, -1},
                    {0, 0}
            };

            int maxExpand =
                    gridMax
                            * gridMax
                            * 50;

            int expanded = 0;

            while (!open.isEmpty()
                    && expanded++ < maxExpand) {

                NodeTime curr =
                        open.poll();

                if (curr.x == goal.x
                        && curr.y == goal.y) {

                    return reconstruct(
                            curr
                    );
                }

                for (int[] dir : dirs) {

                    int nx =
                            curr.x + dir[0];

                    int ny =
                            curr.y + dir[1];

                    if (nx < 0
                            || ny < 0
                            || nx >= gridMax
                            || ny >= gridMax) {

                        continue;
                    }

                    long normalArrive =
                            curr.arrivalTime
                                    + stepMs;

                    SafeArrival safe =
                            earliestSafeArrival(
                                    nx,
                                    ny,
                                    normalArrive
                            );

                    String key =
                            nx + "," + ny;

                    Long known =
                            bestArrival.get(key);

                    if (known != null
                            && known <= safe.time) {

                        continue;
                    }

                    long addedDelay =
                            Math.max(
                                    0,
                                    safe.time
                                            - normalArrive
                            );

                    double waitPenalty =
                            addedDelay
                                    / (double) stepMs;

                    double g =
                            curr.g
                                    + 1.0
                                    + waitPenalty;

                    double f =
                            g
                                    + heuristic(
                                    nx,
                                    ny,
                                    goal.x,
                                    goal.y
                            );

                    NodeTime next =
                            new NodeTime(
                                    nx,
                                    ny,
                                    g,
                                    f,
                                    safe.time,
                                    curr,
                                    curr.adjustmentCount
                                            + safe.adjustments,
                                    curr.delayMs
                                            + addedDelay
                            );

                    bestArrival.put(
                            key,
                            safe.time
                    );

                    open.add(next);
                }
            }

            return SegmentResult.failure();
        }

        private SafeArrival earliestSafeArrival(
                int x,
                int y,
                long arrive
        ) {

            String key =
                    x + "," + y;

            List<TimeWindow> windows =
                    occupiedCells
                            .getOrDefault(
                                    key,
                                    Collections.emptyList()
                            );

            long safe =
                    arrive;

            int adjustments =
                    0;

            boolean adjusted;

            do {

                adjusted = false;

                for (TimeWindow tw : windows) {

                    if (tw.overlaps(
                            safe,
                            safe + stepMs,
                            conflictMarginMs
                    )) {

                        safe =
                                tw.end
                                        + conflictMarginMs
                                        + stepMs;

                        adjustments++;
                        adjusted = true;
                    }
                }

            } while (adjusted);

            return new SafeArrival(
                    safe,
                    adjustments
            );
        }

        private double heuristic(
                int x,
                int y,
                int ex,
                int ey
        ) {

            return Math.abs(x - ex)
                    + Math.abs(y - ey);
        }

        private SegmentResult reconstruct(
                NodeTime node
        ) {

            LinkedList<TimedPoint> path =
                    new LinkedList<>();

            NodeTime curr =
                    node;

            while (curr != null) {

                path.addFirst(
                        new TimedPoint(
                                curr.x,
                                curr.y,
                                curr.arrivalTime
                        )
                );

                curr =
                        curr.parent;
            }

            return new SegmentResult(
                    true,
                    path,
                    node.arrivalTime,
                    node.adjustmentCount,
                    node.delayMs
            );
        }
    }

    static class SafeArrival {

        final long time;
        final int adjustments;

        SafeArrival(
                long time,
                int adjustments
        ) {

            this.time =
                    time;

            this.adjustments =
                    adjustments;
        }
    }

    static class NodeTime {

        final int x;
        final int y;

        final double g;
        final double f;

        final long arrivalTime;

        final NodeTime parent;

        final int adjustmentCount;
        final long delayMs;

        NodeTime(
                int x,
                int y,
                double g,
                double f,
                long arrivalTime,
                NodeTime parent,
                int adjustmentCount,
                long delayMs
        ) {

            this.x = x;
            this.y = y;

            this.g = g;
            this.f = f;

            this.arrivalTime =
                    arrivalTime;

            this.parent =
                    parent;

            this.adjustmentCount =
                    adjustmentCount;

            this.delayMs =
                    delayMs;
        }
    }

    static class SegmentResult {

        final boolean success;

        final List<TimedPoint> path;

        final long endTimeMs;

        final int adjustmentCount;

        final long delayMs;

        SegmentResult(
                boolean success,
                List<TimedPoint> path,
                long endTimeMs,
                int adjustmentCount,
                long delayMs
        ) {

            this.success = success;
            this.path = path;
            this.endTimeMs = endTimeMs;

            this.adjustmentCount =
                    adjustmentCount;

            this.delayMs =
                    delayMs;
        }

        static SegmentResult failure() {

            return new SegmentResult(
                    false,
                    Collections.emptyList(),
                    0,
                    0,
                    0
            );
        }
    }

    static class GridPoint {

        final int x;
        final int y;

        GridPoint(
                int x,
                int y
        ) {

            this.x = x;
            this.y = y;
        }

        String key() {

            return x + "," + y;
        }
    }

    static class TimedPoint {

        final int x;
        final int y;
        final long tMs;

        TimedPoint(
                int x,
                int y,
                long tMs
        ) {

            this.x = x;
            this.y = y;
            this.tMs = tMs;
        }
    }

    static class TimeWindow {

        final long start;
        final long end;

        final String droneId;

        TimeWindow(
                long start,
                long end,
                String droneId
        ) {

            this.start = start;
            this.end = end;
            this.droneId = droneId;
        }

        boolean overlaps(
                long otherStart,
                long otherEnd,
                long marginMs
        ) {

            return !(
                    otherStart
                            > end + marginMs
                            ||
                            otherEnd
                                    < start - marginMs
            );
        }
    }

    static class Candidate {

        final String droneId;

        final double battery;

        final int x;
        final int y;

        final double distanceToPickup;

        Candidate(
                String droneId,
                double battery,
                int x,
                int y,
                double distanceToPickup
        ) {

            this.droneId = droneId;
            this.battery = battery;

            this.x = x;
            this.y = y;

            this.distanceToPickup =
                    distanceToPickup;
        }
    }

    private static double round(
            double value
    ) {

        return Math.round(
                value * 100.0
        ) / 100.0;
    }

    private static void sendError(
            HttpExchange exchange,
            int statusCode,
            String message
    ) throws IOException {

        ObjectNode error =
                MAPPER.createObjectNode();

        error.put("success", false);
        error.put(
                "algorithmConnected",
                true
        );

        error.put(
                "algorithmStage",
                "spatiotemporal-planning"
        );

        error.put(
                "message",
                message
        );

        send(
                exchange,
                statusCode,
                MAPPER.writeValueAsString(error)
        );
    }

    private static void send(
            HttpExchange exchange,
            int statusCode,
            String body
    ) throws IOException {

        byte[] bytes =
                body.getBytes(
                        StandardCharsets.UTF_8
                );

        exchange
                .getResponseHeaders()
                .set(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                );

        exchange
                .getResponseHeaders()
                .set(
                        "Access-Control-Allow-Origin",
                        "*"
                );

        exchange.sendResponseHeaders(
                statusCode,
                bytes.length
        );

        try (
                OutputStream os =
                        exchange.getResponseBody()
        ) {

            os.write(bytes);
        }
    }
}
