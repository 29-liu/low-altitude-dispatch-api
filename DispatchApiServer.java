import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class DispatchApiServer {

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
                  "version": "1.1"
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

        InputStream inputStream = exchange.getRequestBody();

        String requestBody = new String(
                inputStream.readAllBytes(),
                StandardCharsets.UTF_8
        );

        System.out.println("========== Dispatch Request ==========");
        System.out.println(requestBody);
        System.out.println("======================================");

        String result = """
                {
                  "success": true,
                  "api": "dispatch-plan",
                  "stage": "interface-test",
                  "message": "Dispatch request received successfully",
                  "algorithmConnected": false
                }
                """;

        send(exchange, 200, result);
    }

    private static void send(
            HttpExchange exchange,
            int statusCode,
            String body
    ) throws IOException {

        byte[] data = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=UTF-8"
        );

        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Origin",
                "*"
        );

        exchange.sendResponseHeaders(statusCode, data.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }
}
