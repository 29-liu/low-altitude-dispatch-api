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

    // =============================================================
    // V3.4 三维态势前端桥接
    // 保存最近一次调度快照，供 Cesium 前端只读获取。
    // Render 免费实例重启后内存快照会清空；后续可由女娲同步接口重新写入。
    // =============================================================
    private static final Object DASHBOARD_LOCK = new Object();
    private static ObjectNode latestDashboardState = null;

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

        server.createContext(
                "/api/dashboard/state",
                DispatchApiServer::dashboardState
        );

        server.createContext(
                "/api/dashboard/sync",
                DispatchApiServer::dashboardSync
        );

        server.setExecutor(null);
        server.start();

        System.out.println("========================================");
        System.out.println("Low Altitude Dispatch API started");
        System.out.println("Version: 3.4");
        System.out.println("Algorithm: Multi-Constraint Space-Time A*");
        System.out.println("Time mode: status-aware absolute internal / relative output");
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
        result.put("version", "3.4");
        result.put(
                "algorithmConnected",
                true
        );
        result.put(
                "algorithm",
                "Multi-Constraint Space-Time A*"
        );
        result.put(
                "timeMode",
                "status-aware-absolute-internal-relative-output"
        );
        result.put(
                "constraintMode",
                "no-fly+obstacle+crowd-risk+dynamic-occupancy"
        );
        result.put(
                "staticConstraintInput",
                "constraintZones"
        );
        result.put(
                "dashboardStateEndpoint",
                "/api/dashboard/state"
        );
        result.put(
                "dashboardSyncEndpoint",
                "/api/dashboard/sync"
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

            JsonNode constraintZonesNode =
                    root.get("constraintZones");

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
            // 4. 静态三层约束
            //    禁飞区：硬约束
            //    障碍区：硬约束
            //    人群风险区：软约束（风险代价）
            // =====================================================

            ConstraintData constraintData =
                    buildConstraintData(
                            constraintZonesNode,
                            gridMax
                    );

            if (
                    constraintData.hardBlockedCells
                            .contains(start.key())
            ) {

                sendError(
                        exchange,
                        200,
                        "被选无人机当前位置位于禁飞区或障碍区，无法执行本次规划"
                );

                return;
            }

            if (
                    constraintData.hardBlockedCells
                            .contains(pickup.key())
            ) {

                sendError(
                        exchange,
                        200,
                        "取货点位于禁飞区或障碍区，无法执行本次规划"
                );

                return;
            }

            if (
                    constraintData.hardBlockedCells
                            .contains(delivery.key())
            ) {

                sendError(
                        exchange,
                        200,
                        "送达点位于禁飞区或障碍区，无法执行本次规划"
                );

                return;
            }

            // =====================================================
            // 5. 构造未避让基准航迹
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

            int baselineHardConstraintViolationCount =
                    countCellHits(
                            baselinePath,
                            constraintData.hardBlockedCells
                    );

            int baselineCrowdRiskPointCount =
                    countRiskPoints(
                            baselinePath,
                            constraintData.crowdRiskCosts
                    );

            double baselineCrowdRiskCost =
                    calculateRiskCost(
                            baselinePath,
                            constraintData.crowdRiskCosts
                    );

            // =====================================================
            // 6. 当前无人机 → 取货点
            // =====================================================

            SpaceTimeAStar plannerToPickup =
                    new SpaceTimeAStar(
                            gridMax,
                            STEP_MS,
                            CONFLICT_MARGIN_MS,
                            occupiedCells,
                            constraintData.hardBlockedCells,
                            constraintData.crowdRiskCosts
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
            // 7. 取货点 → 配送点
            // =====================================================

            SpaceTimeAStar plannerToDelivery =
                    new SpaceTimeAStar(
                            gridMax,
                            STEP_MS,
                            CONFLICT_MARGIN_MS,
                            occupiedCells,
                            constraintData.hardBlockedCells,
                            constraintData.crowdRiskCosts
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
            // 8. 合并完整路径
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
            // 9. 检查避让后剩余冲突与静态约束
            // =====================================================

            int remainingConflictCount =
                    countPathConflicts(
                            fullPath,
                            occupiedCells
                    );

            int plannedHardConstraintViolationCount =
                    countCellHits(
                            fullPath,
                            constraintData.hardBlockedCells
                    );

            int plannedCrowdRiskPointCount =
                    countRiskPoints(
                            fullPath,
                            constraintData.crowdRiskCosts
                    );

            double plannedCrowdRiskCost =
                    calculateRiskCost(
                            fullPath,
                            constraintData.crowdRiskCosts
                    );

            boolean staticConstraintAdjusted =
                    baselineHardConstraintViolationCount >
                            plannedHardConstraintViolationCount
                            ||
                    baselineCrowdRiskCost >
                            plannedCrowdRiskCost + 1e-9;

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
            // 10. 返回结果
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
                    "Multi-Constraint Space-Time A*"
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
                    "constraintMode",
                    "no-fly+obstacle+crowd-risk+dynamic-occupancy"
            );

            result.put(
                    "constraintZoneCount",
                    constraintData.enabledZoneCount
            );

            result.put(
                    "noFlyZoneCount",
                    constraintData.noFlyZoneCount
            );

            result.put(
                    "obstacleZoneCount",
                    constraintData.obstacleZoneCount
            );

            result.put(
                    "crowdRiskZoneCount",
                    constraintData.crowdRiskZoneCount
            );

            result.put(
                    "baselineHardConstraintViolationCount",
                    baselineHardConstraintViolationCount
            );

            result.put(
                    "plannedHardConstraintViolationCount",
                    plannedHardConstraintViolationCount
            );

            result.put(
                    "baselineCrowdRiskPointCount",
                    baselineCrowdRiskPointCount
            );

            result.put(
                    "plannedCrowdRiskPointCount",
                    plannedCrowdRiskPointCount
            );

            result.put(
                    "baselineCrowdRiskCost",
                    round(baselineCrowdRiskCost)
            );

            result.put(
                    "plannedCrowdRiskCost",
                    round(plannedCrowdRiskCost)
            );

            result.put(
                    "staticConstraintAdjusted",
                    staticConstraintAdjusted
            );

            result.put(
                    "hardConstraintSatisfied",
                    plannedHardConstraintViolationCount == 0
            );

            result.put(
                    "crowdRiskReduced",
                    baselineCrowdRiskCost >
                            plannedCrowdRiskCost + 1e-9
            );

            result.put(
                    "noFlyCellCount",
                    constraintData.noFlyCells.size()
            );

            result.put(
                    "obstacleCellCount",
                    constraintData.obstacleCells.size()
            );

            result.put(
                    "crowdRiskCellCount",
                    constraintData.crowdRiskCosts.size()
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
                    conflictDetected && staticConstraintAdjusted
                            ? "MULTI_CONSTRAINT_SPACE_TIME_AVOIDANCE"
                            : staticConstraintAdjusted
                                    ? "STATIC_CONSTRAINT_AVOIDANCE"
                                    : conflictDetected
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

            if (
                    conflictDetected
                            &&
                    staticConstraintAdjusted
            ) {

                result.put(
                        "message",
                        "检测到静态区域约束与动态时空冲突，已使用多约束时空A*完成联合避让规划"
                );

            } else if (staticConstraintAdjusted) {

                result.put(
                        "message",
                        "检测到禁飞/障碍/人群风险约束，已使用多约束时空A*完成约束感知路径规划"
                );

            } else if (conflictDetected) {

                result.put(
                        "message",
                        "检测到基准航迹存在时空冲突，已使用时空A*完成避让规划"
                );

            } else {

                result.put(
                        "message",
                        constraintData.enabledZoneCount > 0
                                ? "已加载静态三层约束，当前基准航迹无需额外绕行，已完成多约束时空A*路径规划"
                                : "未检测到基准航迹时空冲突，已完成时空A*路径规划"
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

            // V3.4：保存本次成功规划的只读态势快照，
            // Cesium 前端可通过 /api/dashboard/state 获取。
            updateDashboardFromDispatch(
                    root,
                    result
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
    // V3.4 三维态势前端：读取最近一次快照
    // =============================================================

    private static void dashboardState(
            HttpExchange exchange
    ) throws IOException {

        if ("OPTIONS".equalsIgnoreCase(
                exchange.getRequestMethod()
        )) {
            sendOptions(exchange);
            return;
        }

        if (!"GET".equalsIgnoreCase(
                exchange.getRequestMethod()
        )) {
            send(
                    exchange,
                    405,
                    "{\"success\":false,\"message\":\"GET required\"}"
            );
            return;
        }

        ObjectNode out =
                MAPPER.createObjectNode();

        out.put("success", true);
        out.put("version", "3.4");

        synchronized (DASHBOARD_LOCK) {

            if (latestDashboardState == null) {
                out.put("hasData", false);
                out.put(
                        "message",
                        "暂无态势快照，请先完成一次女娲调度任务或调用dashboard同步接口"
                );
            } else {
                out.put("hasData", true);
                out.set(
                        "data",
                        latestDashboardState.deepCopy()
                );
            }
        }

        send(
                exchange,
                200,
                MAPPER.writeValueAsString(out)
        );
    }

    // =============================================================
    // V3.4 三维态势前端：女娲主动同步当前表状态
    //
    // 可传入：
    // drones / flightPlans / constraintZones / tasks / locations
    // 该接口不参与路径规划，仅用于刷新前端展示状态。
    // =============================================================

    private static void dashboardSync(
            HttpExchange exchange
    ) throws IOException {

        if ("OPTIONS".equalsIgnoreCase(
                exchange.getRequestMethod()
        )) {
            sendOptions(exchange);
            return;
        }

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
                            exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8
                    );

            JsonNode incoming =
                    MAPPER.readTree(body);

            if (
                    incoming.has("request_json")
                            && incoming.get("request_json").isTextual()
                            && !incoming.get("request_json").asText().isBlank()
            ) {
                incoming =
                        MAPPER.readTree(
                                incoming.get("request_json").asText()
                        );
            }

            long now =
                    System.currentTimeMillis();

            synchronized (DASHBOARD_LOCK) {

                if (latestDashboardState == null) {
                    latestDashboardState =
                            MAPPER.createObjectNode();
                    latestDashboardState.put(
                            "source",
                            "nvwa-dashboard-sync"
                    );
                }

                latestDashboardState.put(
                        "updatedAtMs",
                        now
                );

                latestDashboardState.put(
                        "lastUpdateType",
                        "sync"
                );

                if (incoming != null && incoming.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields =
                            incoming.fields();

                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry =
                                fields.next();

                        latestDashboardState.set(
                                entry.getKey(),
                                entry.getValue().deepCopy()
                        );
                    }
                }
            }

            ObjectNode out =
                    MAPPER.createObjectNode();

            out.put("success", true);
            out.put("version", "3.4");
            out.put("updatedAtMs", now);
            out.put(
                    "message",
                    "三维态势状态同步成功"
            );

            send(
                    exchange,
                    200,
                    MAPPER.writeValueAsString(out)
            );

        } catch (Exception e) {
            ObjectNode error =
                    MAPPER.createObjectNode();

            error.put("success", false);
            error.put(
                    "message",
                    "态势同步失败：" + e.getMessage()
            );

            send(
                    exchange,
                    500,
                    MAPPER.writeValueAsString(error)
            );
        }
    }

    private static void updateDashboardFromDispatch(
            JsonNode request,
            ObjectNode dispatchResult
    ) {
        ObjectNode snapshot =
                MAPPER.createObjectNode();

        snapshot.put(
                "source",
                "dispatch-plan"
        );
        snapshot.put(
                "updatedAtMs",
                System.currentTimeMillis()
        );
        snapshot.put(
                "lastUpdateType",
                "dispatch"
        );

        if (request != null && request.isObject()) {
            if (request.has("pickup")) {
                snapshot.set(
                        "pickup",
                        request.get("pickup").deepCopy()
                );
            }
            if (request.has("delivery")) {
                snapshot.set(
                        "delivery",
                        request.get("delivery").deepCopy()
                );
            }
            if (request.has("cargoWeight")) {
                snapshot.set(
                        "cargoWeight",
                        request.get("cargoWeight").deepCopy()
                );
            }
            if (request.has("deadlineMin")) {
                snapshot.set(
                        "deadlineMin",
                        request.get("deadlineMin").deepCopy()
                );
            }
            if (request.has("drones")) {
                snapshot.set(
                        "drones",
                        request.get("drones").deepCopy()
                );
            }
            if (request.has("flightPlans")) {
                snapshot.set(
                        "flightPlans",
                        request.get("flightPlans").deepCopy()
                );
            }
            if (request.has("constraintZones")) {
                snapshot.set(
                        "constraintZones",
                        request.get("constraintZones").deepCopy()
                );
            }
        }

        snapshot.set(
                "dispatchResult",
                dispatchResult.deepCopy()
        );

        synchronized (DASHBOARD_LOCK) {
            latestDashboardState = snapshot;
        }
    }

    private static void sendOptions(
            HttpExchange exchange
    ) throws IOException {
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Origin",
                "*"
        );
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Methods",
                "GET,POST,OPTIONS"
        );
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Headers",
                "Content-Type"
        );
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    // =============================================================
    // 静态三层约束解析
    //
    // 支持三种类型：
    // NO_FLY / 禁飞区       -> 硬约束
    // OBSTACLE / 障碍区     -> 硬约束
    // CROWD_RISK / 人群风险区 -> 软约束
    //
    // 每个区域支持：
    // 1. 矩形：xMin/xMax/yMin/yMax
    // 2. 离散栅格：cells:[{x,y},...]
    // 3. 多边形：polygon:[{x,y},...]
    // =============================================================

    private static ConstraintData buildConstraintData(
            JsonNode zones,
            int gridMax
    ) {

        Set<String> noFlyCells =
                new HashSet<>();

        Set<String> obstacleCells =
                new HashSet<>();

        Map<String, Double> crowdRiskCosts =
                new HashMap<>();

        int enabledZoneCount = 0;
        int noFlyZoneCount = 0;
        int obstacleZoneCount = 0;
        int crowdRiskZoneCount = 0;

        if (
                zones == null
                        ||
                !zones.isArray()
        ) {

            return new ConstraintData(
                    noFlyCells,
                    obstacleCells,
                    crowdRiskCosts,
                    0,
                    0,
                    0,
                    0
            );
        }

        for (JsonNode zone : zones) {

            if (
                    zone.has("enabled")
                            &&
                    !zone.path("enabled")
                            .asBoolean(true)
            ) {
                continue;
            }

            String type =
                    normalizeConstraintType(
                            zone.path("type")
                                    .asText("")
                    );

            if (type.isBlank()) {
                continue;
            }

            Set<String> zoneCells =
                    rasterizeZone(
                            zone,
                            gridMax
                    );

            if (zoneCells.isEmpty()) {
                continue;
            }

            enabledZoneCount++;

            if ("NO_FLY".equals(type)) {

                noFlyZoneCount++;
                noFlyCells.addAll(zoneCells);

            } else if ("OBSTACLE".equals(type)) {

                obstacleZoneCount++;
                obstacleCells.addAll(zoneCells);

            } else if ("CROWD_RISK".equals(type)) {

                crowdRiskZoneCount++;

                double riskWeight =
                        Math.max(
                                0.0,
                                zone.path("riskWeight")
                                        .asDouble(4.0)
                        );

                for (String key : zoneCells) {

                    crowdRiskCosts.merge(
                            key,
                            riskWeight,
                            Math::max
                    );
                }
            }
        }

        return new ConstraintData(
                noFlyCells,
                obstacleCells,
                crowdRiskCosts,
                enabledZoneCount,
                noFlyZoneCount,
                obstacleZoneCount,
                crowdRiskZoneCount
        );
    }

    private static String normalizeConstraintType(
            String raw
    ) {

        if (raw == null) {
            return "";
        }

        String text =
                raw.trim();

        String upper =
                text.toUpperCase(
                        Locale.ROOT
                );

        if (
                upper.contains("NO_FLY")
                        ||
                upper.contains("NOFLY")
                        ||
                text.contains("禁飞")
        ) {
            return "NO_FLY";
        }

        if (
                upper.contains("OBSTACLE")
                        ||
                text.contains("障碍")
        ) {
            return "OBSTACLE";
        }

        if (
                upper.contains("CROWD")
                        ||
                upper.contains("RISK")
                        ||
                text.contains("人群")
                        ||
                text.contains("风险")
        ) {
            return "CROWD_RISK";
        }

        return "";
    }

    private static Set<String> rasterizeZone(
            JsonNode zone,
            int gridMax
    ) {

        Set<String> cells =
                new HashSet<>();

        JsonNode explicitCells =
                zone.get("cells");

        if (
                explicitCells != null
                        &&
                explicitCells.isArray()
        ) {

            for (JsonNode cell : explicitCells) {

                int x =
                        cell.path("x")
                                .asInt(-1);

                int y =
                        cell.path("y")
                                .asInt(-1);

                if (
                        x >= 0
                                &&
                        y >= 0
                                &&
                        x < gridMax
                                &&
                        y < gridMax
                ) {

                    cells.add(
                            x + "," + y
                    );
                }
            }

            if (!cells.isEmpty()) {
                return cells;
            }
        }

        if (
                zone.has("xMin")
                        &&
                zone.has("xMax")
                        &&
                zone.has("yMin")
                        &&
                zone.has("yMax")
        ) {

            int xMin =
                    Math.max(
                            0,
                            Math.min(
                                    zone.path("xMin").asInt(),
                                    zone.path("xMax").asInt()
                            )
                    );

            int xMax =
                    Math.min(
                            gridMax - 1,
                            Math.max(
                                    zone.path("xMin").asInt(),
                                    zone.path("xMax").asInt()
                            )
                    );

            int yMin =
                    Math.max(
                            0,
                            Math.min(
                                    zone.path("yMin").asInt(),
                                    zone.path("yMax").asInt()
                            )
                    );

            int yMax =
                    Math.min(
                            gridMax - 1,
                            Math.max(
                                    zone.path("yMin").asInt(),
                                    zone.path("yMax").asInt()
                            )
                    );

            for (int x = xMin; x <= xMax; x++) {
                for (int y = yMin; y <= yMax; y++) {
                    cells.add(
                            x + "," + y
                    );
                }
            }

            if (!cells.isEmpty()) {
                return cells;
            }
        }

        JsonNode polygon =
                zone.get("polygon");

        if (
                polygon != null
                        &&
                polygon.isArray()
                        &&
                polygon.size() >= 3
        ) {

            List<GridPoint> points =
                    new ArrayList<>();

            for (JsonNode point : polygon) {

                points.add(
                        new GridPoint(
                                point.path("x").asInt(),
                                point.path("y").asInt()
                        )
                );
            }

            int minX = gridMax - 1;
            int maxX = 0;
            int minY = gridMax - 1;
            int maxY = 0;

            for (GridPoint point : points) {

                minX =
                        Math.min(
                                minX,
                                point.x
                        );

                maxX =
                        Math.max(
                                maxX,
                                point.x
                        );

                minY =
                        Math.min(
                                minY,
                                point.y
                        );

                maxY =
                        Math.max(
                                maxY,
                                point.y
                        );
            }

            minX =
                    Math.max(
                            0,
                            minX
                    );

            minY =
                    Math.max(
                            0,
                            minY
                    );

            maxX =
                    Math.min(
                            gridMax - 1,
                            maxX
                    );

            maxY =
                    Math.min(
                            gridMax - 1,
                            maxY
                    );

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {

                    if (
                            pointInPolygon(
                                    x + 0.5,
                                    y + 0.5,
                                    points
                            )
                    ) {

                        cells.add(
                                x + "," + y
                        );
                    }
                }
            }
        }

        return cells;
    }

    private static boolean pointInPolygon(
            double x,
            double y,
            List<GridPoint> polygon
    ) {

        boolean inside = false;

        for (
                int i = 0,
                j = polygon.size() - 1;
                i < polygon.size();
                j = i++
        ) {

            double xi = polygon.get(i).x;
            double yi = polygon.get(i).y;

            double xj = polygon.get(j).x;
            double yj = polygon.get(j).y;

            boolean intersect =
                    ((yi > y) != (yj > y))
                            &&
                    (x <
                            (xj - xi)
                                    *
                            (y - yi)
                                    /
                            ((yj - yi) == 0.0
                                    ? 1e-12
                                    : (yj - yi))
                                    + xi);

            if (intersect) {
                inside = !inside;
            }
        }

        return inside;
    }

    private static int countCellHits(
            List<TimedPoint> path,
            Set<String> cells
    ) {

        int hits = 0;

        for (TimedPoint point : path) {

            if (
                    cells.contains(
                            point.x + "," + point.y
                    )
            ) {
                hits++;
            }
        }

        return hits;
    }

    private static int countRiskPoints(
            List<TimedPoint> path,
            Map<String, Double> riskCosts
    ) {

        int count = 0;

        for (TimedPoint point : path) {

            if (
                    riskCosts.containsKey(
                            point.x + "," + point.y
                    )
            ) {
                count++;
            }
        }

        return count;
    }

    private static double calculateRiskCost(
            List<TimedPoint> path,
            Map<String, Double> riskCosts
    ) {

        double cost = 0.0;

        for (TimedPoint point : path) {

            cost +=
                    riskCosts.getOrDefault(
                            point.x + "," + point.y,
                            0.0
                    );
        }

        return cost;
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

            // =====================================================
            // V3.2 时间语义
            //
            // 已分配：
            //   尚未真正开始执行，因此不能继续使用“确认执行”时写入的绝对时间。
            //   每次有新任务进行规划时，将该计划的第一个路径点重新锚定到
            //   planningStartTimeMs，并保留原路径点之间的相对时间差。
            //
            // 执行中：
            //   认为 start_time_ms / path.tMs 已经代表真实仿真执行时间，
            //   因此继续按数据库中的绝对时间使用。
            // =====================================================

            boolean assignedPlan =
                    "已分配".equals(status);

            long assignedFirstRawTime = 0;

            if (assignedPlan) {

                assignedFirstRawTime =
                        resolveExistingPointTime(
                                path.get(0),
                                rawStartTimeMs,
                                0,
                                stepMs,
                                planningStartTimeMs
                        );
            }

            // 判断该计划是否已经完全过期。
            // 已分配计划永远按“当前规划时刻”重新锚定，因此不会因为
            // 用户确认后等待了一段时间而被误判为过期。
            JsonNode lastPoint =
                    path.get(
                            path.size() - 1
                    );

            long lastRawTime =
                    resolveExistingPointTime(
                            lastPoint,
                            rawStartTimeMs,
                            path.size() - 1,
                            stepMs,
                            planningStartTimeMs
                    );

            long lastTime;

            if (assignedPlan) {

                long relativeOffset =
                        Math.max(
                                0,
                                lastRawTime
                                        - assignedFirstRawTime
                        );

                lastTime =
                        planningStartTimeMs
                                + relativeOffset;

            } else {

                lastTime =
                        lastRawTime;
            }

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

                long rawPointTime =
                        resolveExistingPointTime(
                                point,
                                rawStartTimeMs,
                                i,
                                stepMs,
                                planningStartTimeMs
                        );

                long t;

                if (assignedPlan) {

                    long relativeOffset =
                            Math.max(
                                    0,
                                    rawPointTime
                                            - assignedFirstRawTime
                            );

                    t =
                            planningStartTimeMs
                                    + relativeOffset;

                } else {

                    t =
                            rawPointTime;
                }

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

        JsonNode zones =
                root.get("constraintZones");

        if (
                zones != null
                        &&
                zones.isArray()
        ) {

            for (JsonNode zone : zones) {

                max =
                        Math.max(
                                max,
                                Math.max(
                                        zone.path("xMin").asInt(0),
                                        zone.path("xMax").asInt(0)
                                )
                        );

                max =
                        Math.max(
                                max,
                                Math.max(
                                        zone.path("yMin").asInt(0),
                                        zone.path("yMax").asInt(0)
                                )
                        );

                JsonNode cells =
                        zone.get("cells");

                if (
                        cells != null
                                &&
                        cells.isArray()
                ) {

                    for (JsonNode cell : cells) {

                        max =
                                Math.max(
                                        max,
                                        Math.max(
                                                cell.path("x").asInt(0),
                                                cell.path("y").asInt(0)
                                        )
                                );
                    }
                }

                JsonNode polygon =
                        zone.get("polygon");

                if (
                        polygon != null
                                &&
                        polygon.isArray()
                ) {

                    for (JsonNode point : polygon) {

                        max =
                                Math.max(
                                        max,
                                        Math.max(
                                                point.path("x").asInt(0),
                                                point.path("y").asInt(0)
                                        )
                                );
                    }
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

        final Set<String>
                hardBlockedCells;

        final Map<String, Double>
                crowdRiskCosts;

        SpaceTimeAStar(
                int gridMax,
                long stepMs,
                long conflictMarginMs,
                Map<String, List<TimeWindow>>
                        occupiedCells,
                Set<String>
                        hardBlockedCells,
                Map<String, Double>
                        crowdRiskCosts
        ) {

            this.gridMax =
                    gridMax;

            this.stepMs =
                    stepMs;

            this.conflictMarginMs =
                    conflictMarginMs;

            this.occupiedCells =
                    occupiedCells;

            this.hardBlockedCells =
                    hardBlockedCells;

            this.crowdRiskCosts =
                    crowdRiskCosts;
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

            Map<String, Double>
                    bestCost =
                    new HashMap<>();

            boolean riskAwareSearch =
                    !crowdRiskCosts.isEmpty();

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

            bestCost.put(
                    start.key(),
                    0.0
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

                    String key =
                            nx + "," + ny;

                    if (
                            hardBlockedCells.contains(
                                    key
                            )
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

                    double riskPenalty =
                            crowdRiskCosts
                                    .getOrDefault(
                                            key,
                                            0.0
                                    );

                    double g =
                            curr.g
                                    + 1.0
                                    + waitPenalty
                                    + riskPenalty;

                    if (riskAwareSearch) {

                        Double knownCost =
                                bestCost.get(
                                        key
                                );

                        if (
                                knownCost != null
                                        &&
                                knownCost <= g
                        ) {
                            continue;
                        }

                    } else {

                        Long known =
                                bestArrival.get(
                                        key
                                );

                        if (
                                known != null
                                        &&
                                known <= safe.time
                        ) {
                            continue;
                        }
                    }

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

                    bestCost.put(
                            key,
                            g
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

    static class ConstraintData {

        final Set<String> noFlyCells;
        final Set<String> obstacleCells;
        final Set<String> hardBlockedCells;
        final Map<String, Double> crowdRiskCosts;

        final int enabledZoneCount;
        final int noFlyZoneCount;
        final int obstacleZoneCount;
        final int crowdRiskZoneCount;

        ConstraintData(
                Set<String> noFlyCells,
                Set<String> obstacleCells,
                Map<String, Double> crowdRiskCosts,
                int enabledZoneCount,
                int noFlyZoneCount,
                int obstacleZoneCount,
                int crowdRiskZoneCount
        ) {

            this.noFlyCells =
                    noFlyCells;

            this.obstacleCells =
                    obstacleCells;

            this.hardBlockedCells =
                    new HashSet<>();

            this.hardBlockedCells
                    .addAll(noFlyCells);

            this.hardBlockedCells
                    .addAll(obstacleCells);

            this.crowdRiskCosts =
                    crowdRiskCosts;

            this.enabledZoneCount =
                    enabledZoneCount;

            this.noFlyZoneCount =
                    noFlyZoneCount;

            this.obstacleZoneCount =
                    obstacleZoneCount;

            this.crowdRiskZoneCount =
                    crowdRiskZoneCount;
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

        exchange
                .getResponseHeaders()
                .set(
                        "Access-Control-Allow-Methods",
                        "GET,POST,OPTIONS"
                );

        exchange
                .getResponseHeaders()
                .set(
                        "Access-Control-Allow-Headers",
                        "Content-Type"
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
