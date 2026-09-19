import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DispatchApiServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8088")
        );

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port), 0
        );

        server.createContext("/api/health", DispatchApiServer::health);
        server.createContext("/api/dispatch/plan", DispatchApiServer::dispatchPlan);

        server.setExecutor(null);
        server.start();

        System.out.println("====================================");
        System.out.println("Low Altitude Dispatch API started");
        System.out.println("Port: " + port);
        System.out.println("GET  /api/health");
        System.out.println("POST /api/dispatch/plan");
        System.out.println("====================================");
    }

    private static void health(HttpExchange exchange) throws IOException {

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405,
                    "{\"success\":false,\"message\":\"Method Not Allowed\"}");
            return;
        }

        String json = """
                {
                  "success": true,
                  "service": "Low Altitude Dispatch API",
                  "status": "running",
                  "version": "2.0",
                  "algorithmConnected": true
                }
                """;

        send(exchange, 200, json);
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

            // 兼容女娲把完整算法输入作为 request_json 字符串发送过来
            if (root.has("request_json")
                    && root.get("request_json").isTextual()
                    && !root.get("request_json").asText().isBlank()) {

                root = MAPPER.readTree(
                        root.get("request_json").asText()
                );
            }

            JsonNode pickup = root.get("pickup");
            JsonNode delivery = root.get("delivery");
            JsonNode dronesNode = root.get("drones");

            double cargoWeight = root.path("cargoWeight").asDouble(0);
            double deadlineMin = root.path("deadlineMin").asDouble(0);

            if (pickup == null || delivery == null) {
                send(exchange, 400,
                        MAPPER.writeValueAsString(
                                MAPPER.createObjectNode()
                                        .put("success", false)
                                        .put("message", "缺少取货点或送达点坐标")
                        )
                );
                return;
            }

            if (dronesNode == null || !dronesNode.isArray()) {
                send(exchange, 400,
                        MAPPER.writeValueAsString(
                                MAPPER.createObjectNode()
                                        .put("success", false)
                                        .put("message", "缺少无人机状态数据")
                        )
                );
                return;
            }

            double pickupX = pickup.path("x").asDouble();
            double pickupY = pickup.path("y").asDouble();
            double deliveryX = delivery.path("x").asDouble();
            double deliveryY = delivery.path("y").asDouble();

            List<Candidate> candidates = new ArrayList<>();
            var excluded = MAPPER.createArrayNode();

            for (JsonNode drone : dronesNode) {

                String droneId = drone.path("droneId").asText("");
                String status = drone.path("status").asText("");
                double battery = drone.path("battery").asDouble(0);
                double payloadCapacity = drone.path("payloadCapacity").asDouble(0);
                double currentPayload = drone.path("currentPayload").asDouble(0);

                double x = drone.path("x").asDouble(0);
                double y = drone.path("y").asDouble(0);

                String reason = null;

                if (!"可用".equals(status)) {
                    reason = "状态不是可用";
                } else if (battery < 20) {
                    reason = "电量低于20%";
                } else if ((payloadCapacity - currentPayload) < cargoWeight) {
                    reason = "剩余载荷能力不足";
                }

                if (reason != null) {
                    var excludedNode = MAPPER.createObjectNode();
                    excludedNode.put("droneId", droneId);
                    excludedNode.put("reason", reason);
                    excluded.add(excludedNode);
                    continue;
                }

                double distanceToPickup =
                        Math.hypot(x - pickupX, y - pickupY);

                double deliveryDistance =
                        Math.hypot(deliveryX - pickupX,
                                   deliveryY - pickupY);

                double totalGridDistance =
                        distanceToPickup + deliveryDistance;

                candidates.add(
                        new Candidate(
                                droneId,
                                battery,
                                x,
                                y,
                                distanceToPickup,
                                totalGridDistance
                        )
                );
            }

            if (candidates.isEmpty()) {

                var result = MAPPER.createObjectNode();

                result.put("success", false);
                result.put("algorithmConnected", true);
                result.put("algorithmStage", "basic-selection");
                result.put("message", "当前没有满足条件的可用无人机");
                result.set("excludedDrones", excluded);

                send(exchange, 200,
                        MAPPER.writeValueAsString(result));
                return;
            }

            // 第一优先：距离取货点最近
            // 第二优先：电量更高
            candidates.sort(
                    Comparator
                            .comparingDouble(
                                    (Candidate c) -> c.distanceToPickup
                            )
                            .thenComparing(
                                    Comparator.comparingDouble(
                                            (Candidate c) -> c.battery
                                    ).reversed()
                            )
            );

            Candidate selected = candidates.get(0);

            var result = MAPPER.createObjectNode();

            result.put("success", true);
            result.put("algorithmConnected", true);
            result.put("algorithmStage", "basic-selection");

            result.put("assignedDrone", selected.droneId);
            result.put("battery", selected.battery);

            result.put("distanceToPickup",
                    round(selected.distanceToPickup));

            result.put("totalGridDistance",
                    round(selected.totalGridDistance));

            result.put("cargoWeight", cargoWeight);
            result.put("deadlineMin", deadlineMin);

            result.put("message",
                    "已根据状态、电量、载荷能力和取货距离完成基础无人机筛选");

            var pickupResult = MAPPER.createObjectNode();
            pickupResult.put("x", pickupX);
            pickupResult.put("y", pickupY);

            var deliveryResult = MAPPER.createObjectNode();
            deliveryResult.put("x", deliveryX);
            deliveryResult.put("y", deliveryY);

            result.set("pickup", pickupResult);
            result.set("delivery", deliveryResult);
            result.set("excludedDrones", excluded);

            send(exchange, 200,
                    MAPPER.writeValueAsString(result));

        } catch (Exception e) {

            e.printStackTrace();

            var error = MAPPER.createObjectNode();

            error.put("success", false);
            error.put("algorithmConnected", true);
            error.put("message",
                    "算法请求解析失败：" + e.getMessage());

            send(exchange, 500,
                    MAPPER.writeValueAsString(error));
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void send(
            HttpExchange exchange,
            int statusCode,
            String body
    ) throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=UTF-8"
        );

        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Origin",
                "*"
        );

        exchange.sendResponseHeaders(
                statusCode,
                bytes.length
        );

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static class Candidate {

        String droneId;
        double battery;
        double x;
        double y;
        double distanceToPickup;
        double totalGridDistance;

        Candidate(
                String droneId,
                double battery,
                double x,
                double y,
                double distanceToPickup,
                double totalGridDistance
        ) {
            this.droneId = droneId;
            this.battery = battery;
            this.x = x;
            this.y = y;
            this.distanceToPickup = distanceToPickup;
            this.totalGridDistance = totalGridDistance;
        }
    }
}
