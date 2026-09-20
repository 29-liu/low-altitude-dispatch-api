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

    private static final long STEP_MS = 1000L;
    private static final long CONFLICT_MARGIN_MS = 5000L;

    // 用于判断一个时间是不是13位绝对时间戳
    private static final long ABSOLUTE_TIME_THRESHOLD = 1_000_000_000_000L;

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8088")
        );

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port),
                0
        );

        server.createContext(
                "/api/health",
                DispatchApiServer::health
        );

        server.createContext(
                "/api/dispatch/plan",
                DispatchApiServer::dispatchPlan
        );

        server.setExecutor(null);
        server.start();

        System.out.println("========================================");
        System.out.println("Low Altitude Dispatch API started");
        System.out.println("Version: 3.1");
        System.out.println("Algorithm: Space-Time A*");
        System.out.println("Time mode: absolute internal / relative output");
        System.out.println("Port: " + port);
        System.out.println("========================================");
    }

    private static void health(
            HttpExchange exchange
    ) throws IOException {

        if (!"GET".equalsIgnoreCase(
                exchange.getRequestMethod()
        )) {

            send(
                    exchange,
                    405,
                    "{\"success\":false,\"message\":\"Method Not Allowed\"}"
            );

            return;
        }

        ObjectNode result =
                MAPPER.createObjectNode();

        result.put("success", true);
        result.put(
                "service",
                "Low Altitude Dispatch API"
        );
        result.put("status", "running");
        result.put("version", "3.1");
        result.put(
                "algorithmConnected",
                true
        );
        result.put(
                "algorithm",
                "Space-Time A*"
        );
        result.put(
                "timeMode",
                "absolute-internal-relative-output"
        );

        send(
                exchange,
                200,
                MAPPER.writeValueAsString(result)
        );
    }

    private static void dispatchPlan(
            HttpExchange exchange
    ) throws IOException {

        if (!"POST".equalsIgnoreCase(
                exchange.getRequestMethod()
        )) {

            send(
                    exchange,
                    405,
                    "{\"success\":false,\"message\":\"POST required\"}"
            );

            return;
        }

        try {

            String body =
                    new String(
                            exchange
                                    .getRequestBody()
                                    .readAllBytes(),
                            StandardCharsets.UTF_8
                    );

            JsonNode root =
                    MAPPER.readTree(body);

            // 女娲HTTP节点当前发送的是：
            // {"request_json":"..."}
            if (
                    root.has("request_json")
                            &&
                    root.get("request_json")
                            .isTextual()
                            &&
                    !root.get("request_json")
                            .asText()
                            .isBlank()
            ) {

                root =
                        MAPPER.readTree(
                                root
                                        .get("request_json")
                                        .asText()
                        );
            }

            JsonNode pickupNode =
                    root.get("pickup");

            JsonNode deliveryNode =
                    root.get("delivery");

            JsonNode dronesNode =
                    root.get("drones");

            JsonNode flightPlansNode =
                    root.get("flightPlans");

            if (
                    pickupNode == null
                            ||
                    deliveryNode == null
            ) {

                sendError(
                        exchange,
                        400,
                        "缺少取货点或送达点坐标"
                );

                return;
            }

            if (
                    dronesNode == null
                            ||
                    !dronesNode.isArray()
            ) {

                sendError(
                        exchange,
                        400,
                        "缺少无人机状态数据"
                );

                return;
            }

            double cargoWeight =
                    root.path("cargoWeight")
                            .asDouble(0);

            double deadlineMin =
                    root.path("deadlineMin")
                            .asDouble(0);

            GridPoint pickup =
                    new GridPoint(
                            pickupNode
                                    .path("x")
                                    .asInt(),
                            pickupNode
                                    .path("y")
                                    .asInt()
                    );

            GridPoint delivery =
                    new GridPoint(
                            deliveryNode
                                    .path("x")
                                    .asInt(),
                            deliveryNode
                                    .path("y")
                                    .asInt()
                    );

            // =====================================================
            // 统一的本次规划绝对时间基准
            // =====================================================

            long planningStartTimeMs =
                    System.currentTimeMillis();

            // =====================================================
            // 1. 无人机基础筛选
            // =====================================================

            List<Candidate> candidates =
                    new ArrayList<>();

            ArrayNode excluded =
                    MAPPER.createArrayNode();

            for (JsonNode drone : dronesNode) {

                String droneId =
                        drone.path("droneId")
                                .asText("");

                String status =
                        drone.path("status")
                                .asText("");

                double battery =
                        drone.path("battery")
                                .asDouble(0);

                double payloadCapacity =
                        drone.path("payloadCapacity")
                                .asDouble(0);

                double currentPayload =
                        drone.path("currentPayload")
                                .asDouble(0);

                int x =
                        drone.path("x")
                                .asInt();

                int y =
                        drone.path("y")
                                .asInt();

                String reason = null;

                if (!"可用".equals(status)) {

                    reason =
                            "状态不是可用";

                } else if (battery < 20.0) {

                    reason =
                            "电量低于20%";

                } else if (
                        payloadCapacity
                                - currentPayload
                                < cargoWeight
                ) {

                    reason =
                            "剩余载荷能力不足";
                }

                if (reason != null) {

                    ObjectNode item =
                            MAPPER.createObjectNode();

                    item.put(
                            "droneId",
                            droneId
                    );

                    item.put(
                            "reason",
                            reason
                    );

                    excluded.add(item);

                    continue;
                }

                double distanceToPickup =
                        Math.hypot(
                                x - pickup.x,
                                y - pickup.y
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

                result.put(
                        "success",
                        false
                );

                result.put(
                        "algorithmConnected",
                        true
                );

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
                        MAPPER.writeValueAsString(
                                result
                        )
                );

                return;
            }

            // 距离优先；距离相同时电量优先
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

            // =====================================================
            // 2. 已有飞行计划 → occupiedCells
            // =====================================================

            OccupancyData occupancyData =
                    buildOccupiedCells(
                            flightPlansNode,
                            selected.droneId,
                            planningStartTimeMs
                    );

            Map<String, List<TimeWindow>>
                    occupiedCells =
                    occupancyData.occupiedCells;

            // =====================================================
            // 3. 动态网格范围
            // =====================================================

            int gridMax =
                    calculateGridMax(
                            root,
                            start,
                            pickup,
                            delivery
                    );

            // =====================================================
            // 4. 构造未避让基准航迹
            // =====================================================

            List<TimedPoint> baselinePath =
                    buildBaselinePath(
                            start,
                            pickup,
                            delivery,
                            STEP_MS,
                            planningStartTimeMs
                    );

            int baselineConflictCount =
                    countPathConflicts(
                            baselinePath,
                            occupiedCells
                    );

            // =====================================================
            // 5. 当前无人机 → 取货点
            // =====================================================

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
                            planningStartTimeMs
                    );

            if (!toPickup.success) {

                sendError(
                        exchange,
                        200,
                        "无人机到取货点的时空A*路径规划失败"
                );

                return;
            }

            // =====================================================
            // 6. 取货点 → 配送点
            // =====================================================

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

            // =====================================================
            // 7. 合并完整路径
            // =====================================================

            List<TimedPoint> fullPath =
                    new ArrayList<>(
                            toPickup.path
                    );

            if (
                    toDelivery.path.size() > 1
            ) {

                fullPath.addAll(
                        toDelivery.path
                                .subList(
                                        1,
                                        toDelivery.path.size()
                                )
                );
            }

            // =====================================================
            // 8. 检查避让后剩余冲突
            // =====================================================

            int remainingConflictCount =
                    countPathConflicts(
                            fullPath,
                            occupiedCells
                    );

            long endTimeMs =
                    toDelivery.endTimeMs;

            long totalDurationMs =
                    Math.max(
                            0,
                            endTimeMs
                                    - planningStartTimeMs
                    );

            double estimatedTimeMin =
                    totalDurationMs
                            / 60000.0;

            boolean deadlineSatisfied =
                    deadlineMin <= 0
                            ||
                    estimatedTimeMin
                            <= deadlineMin;

            int avoidanceCount =
                    toPickup.adjustmentCount
                            +
                    toDelivery.adjustmentCount;

            long avoidanceDelayMs =
                    toPickup.delayMs
                            +
                    toDelivery.delayMs;

            boolean conflictDetected =
                    baselineConflictCount > 0;

            boolean conflictResolved =
                    conflictDetected
                            &&
                    remainingConflictCount == 0;

            // =====================================================
            // 9. 返回结果
            // =====================================================

            ObjectNode result =
                    MAPPER.createObjectNode();

            result.put(
                    "success",
                    true
            );

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
                    "planningStartTimeMs",
                    planningStartTimeMs
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
                    round(
                            estimatedTimeMin
                    )
            );

            result.put(
                    "deadlineSatisfied",
                    deadlineSatisfied
            );

            result.put(
                    "activeFlightPlanCount",
                    occupancyData.activePlanCount
            );

            // Java内部使用绝对时间，
            // 对外仍返回相对本次规划开始的时间
            result.put(
                    "pathTimeMode",
                    "relative"
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

                p.put(
                        "x",
                        point.x
                );

                p.put(
                        "y",
                        point.y
                );

                // 转换成相对时间再返回
                p.put(
                        "tMs",
                        Math.max(
                                0,
                                point.tMs
                                        - planningStartTimeMs
                        )
                );

                pathArray.add(p);
            }

            result.set(
                    "path",
                    pathArray
            );

            send(
                    exchange,
                    200,
                    MAPPER.writeValueAsString(
                            result
                    )
            );

        } catch (Exception e) {

            e.printStackTrace();

            ObjectNode error =
                    MAPPER.createObjectNode();

            error.put(
                    "success",
                    false
            );

            error.put(
                    "algorithmConnected",
                    true
            );

            error.put(
                    "message",
                    "算法请求解析失败："
                            + e.getMessage()
            );

            send(
                    exchange,
                    500,
                    MAPPER.writeValueAsString(
                            error
                    )
            );
        }
    }

    // =============================================================
    // 已有飞行计划转为空域时间占用
    //
    // 同时兼容：
    // 1. 正式绝对时间飞行计划
    // 2. 当前两条旧测试相对时间飞行计划
    // =============================================================

    private static OccupancyData buildOccupiedCells(
            JsonNode plans,
            String selectedDrone,
            long planningStartTimeMs
    ) {

        Map<String, List<TimeWindow>>
                occupied =
                new HashMap<>();

        int activePlanCount = 0;

        if (
                plans == null
                        ||
                !plans.isArray()
        ) {

            return new OccupancyData(
                    occupied,
                    0
            );
        }

        for (JsonNode plan : plans) {

            String droneId =
                    plan.path("droneId")
                            .asText("");

            // 不和本次被选中的无人机自身已有计划冲突
            if (
                    selectedDrone.equals(
                            droneId
                    )
            ) {
                continue;
            }

            String status =
                    plan.path("status")
                            .asText("");

            if (
                    !"执行中".equals(status)
                            &&
                    !"已分配".equals(status)
            ) {

                continue;
            }

            long rawStartTimeMs =
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

            if (
                    path == null
                            ||
                    !path.isArray()
                            ||
                    path.size() == 0
            ) {

                continue;
            }

            // 判断该计划是否已经完全过期
            JsonNode lastPoint =
                    path.get(
                            path.size() - 1
                    );

            long lastTime =
                    resolveExistingPointTime(
                            lastPoint,
                            rawStartTimeMs,
                            path.size() - 1,
                            stepMs,
                            planningStartTimeMs
                    );

            if (
                    lastTime
                            + CONFLICT_MARGIN_MS
                            < planningStartTimeMs
            ) {

                continue;
            }

            activePlanCount++;

            for (
                    int i = 0;
                    i < path.size();
                    i++
            ) {

                JsonNode point =
                        path.get(i);

                int x =
                        point.path("x")
                                .asInt();

                int y =
                        point.path("y")
                                .asInt();

                long t =
                        resolveExistingPointTime(
                                point,
                                rawStartTimeMs,
                                i,
                                stepMs,
                                planningStartTimeMs
                        );

                String key =
                        x + "," + y;

                occupied
                        .computeIfAbsent(
                                key,
                                k ->
                                        new ArrayList<>()
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

        return new OccupancyData(
                occupied,
                activePlanCount
        );
    }

    private static long resolveExistingPointTime(
            JsonNode point,
            long rawStartTimeMs,
            int index,
            long stepMs,
            long planningStartTimeMs
    ) {

        if (point.has("tMs")) {

            long rawPointTime =
                    point.path("tMs")
                            .asLong();

            // 已经是绝对时间
            if (
                    rawPointTime
                            > ABSOLUTE_TIME_THRESHOLD
            ) {

                return rawPointTime;
            }

            // 正式计划的start_time_ms是绝对时间，
            // path中的tMs仍是相对时间
            if (
                    rawStartTimeMs
                            > ABSOLUTE_TIME_THRESHOLD
            ) {

                return rawStartTimeMs
                        + rawPointTime;
            }

            // 兼容旧的相对时间测试计划
            return planningStartTimeMs
                    + rawStartTimeMs
                    + rawPointTime;
        }

        // path没有tMs，但start_time_ms是正式绝对时间
        if (
                rawStartTimeMs
                        > ABSOLUTE_TIME_THRESHOLD
        ) {

            return rawStartTimeMs
                    + index * stepMs;
        }

        // 兼容现在数据库里的
        // -31000 / 300000 两条测试计划
        return planningStartTimeMs
                + rawStartTimeMs
                + index * stepMs;
    }

    // =============================================================
    // 动态网格范围
    // =============================================================

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

        if (
                drones != null
                        &&
                drones.isArray()
        ) {

            for (JsonNode drone : drones) {

                max =
                        Math.max(
                                max,
                                Math.max(
                                        drone.path("x")
                                                .asInt(),
                                        drone.path("y")
                                                .asInt()
                                )
                        );
            }
        }

        JsonNode plans =
                root.get("flightPlans");

        if (
                plans != null
                        &&
                plans.isArray()
        ) {

            for (JsonNode plan : plans) {

                JsonNode path =
                        plan.get("path");

                if (
                        path == null
                                ||
                        !path.isArray()
                ) {

                    continue;
                }

                for (JsonNode p : path) {

                    max =
                            Math.max(
                                    max,
                                    Math.max(
                                            p.path("x")
                                                    .asInt(),
                                            p.path("y")
                                                    .asInt()
                                    )
                            );
                }
            }
        }

        return Math.max(
                100,
                max + 10
        );
    }

    // =============================================================
    // 基准曼哈顿路径
    // =============================================================

    private static List<TimedPoint> buildBaselinePath(
            GridPoint start,
            GridPoint pickup,
            GridPoint delivery,
            long stepMs,
            long startTimeMs
    ) {

        List<TimedPoint> path =
                new ArrayList<>();

        long time =
                startTimeMs;

        int x =
                start.x;

        int y =
                start.y;

        path.add(
                new TimedPoint(
                        x,
                        y,
                        time
                )
        );

        while (x != pickup.x) {

            x +=
                    Integer.compare(
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

            y +=
                    Integer.compare(
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

            x +=
                    Integer.compare(
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

            y +=
                    Integer.compare(
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

    // =============================================================
    // 路径冲突统计
    // =============================================================

    private static int countPathConflicts(
            List<TimedPoint> path,
            Map<String, List<TimeWindow>>
                    occupied
    ) {

        int conflicts = 0;

        for (TimedPoint point : path) {

            String key =
                    point.x
                            + ","
                            + point.y;

            List<TimeWindow> windows =
                    occupied
                            .getOrDefault(
                                    key,
                                    Collections.emptyList()
                            );

            for (TimeWindow tw : windows) {

                if (
                        tw.overlaps(
                                point.tMs,
                                point.tMs
                                        + STEP_MS,
                                CONFLICT_MARGIN_MS
                        )
                ) {

                    conflicts++;
                }
            }
        }

        return conflicts;
    }

    // =============================================================
    // Space-Time A*
    // =============================================================

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
                                            n ->
                                                    n.f
                                    )
                    );

            Map<String, Long>
                    bestArrival =
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

            open.add(
                    startNode
            );

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

            while (
                    !open.isEmpty()
                            &&
                    expanded++
                            < maxExpand
            ) {

                NodeTime curr =
                        open.poll();

                if (
                        curr.x == goal.x
                                &&
                        curr.y == goal.y
                ) {

                    return reconstruct(
                            curr
                    );
                }

                for (int[] dir : dirs) {

                    int nx =
                            curr.x
                                    + dir[0];

                    int ny =
                            curr.y
                                    + dir[1];

                    if (
                            nx < 0
                                    ||
                            ny < 0
                                    ||
                            nx >= gridMax
                                    ||
                            ny >= gridMax
                    ) {

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
                            bestArrival.get(
                                    key
                            );

                    if (
                            known != null
                                    &&
                            known
                                    <= safe.time
                    ) {

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
                                    /
                            (double) stepMs;

                    double g =
                            curr.g
                                    + 1.0
                                    + waitPenalty;

                    double f =
                            g
                                    +
                            heuristic(
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

                    open.add(
                            next
                    );
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

                    if (
                            tw.overlaps(
                                    safe,
                                    safe + stepMs,
                                    conflictMarginMs
                            )
                    ) {

                        safe =
                                tw.end
                                        + conflictMarginMs
                                        + stepMs;

                        adjustments++;

                        adjusted =
                                true;
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

            return Math.abs(
                    x - ex
            )
                    +
                    Math.abs(
                            y - ey
                    );
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

    static class OccupancyData {

        final Map<String, List<TimeWindow>>
                occupiedCells;

        final int activePlanCount;

        OccupancyData(
                Map<String, List<TimeWindow>>
                        occupiedCells,
                int activePlanCount
        ) {

            this.occupiedCells =
                    occupiedCells;

            this.activePlanCount =
                    activePlanCount;
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

            this.success =
                    success;

            this.path =
                    path;

            this.endTimeMs =
                    endTimeMs;

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

            this.tMs =
                    tMs;
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

            this.start =
                    start;

            this.end =
                    end;

            this.droneId =
                    droneId;
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

            this.droneId =
                    droneId;

            this.battery =
                    battery;

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

        error.put(
                "success",
                false
        );

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
                MAPPER.writeValueAsString(
                        error
                )
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
                        exchange
                                .getResponseBody()
        ) {

            os.write(bytes);
        }
    }
}
