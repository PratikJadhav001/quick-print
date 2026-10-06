import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency-light Java 11 server for the supplied QuickPrint frontend.
 * It exposes only the endpoints used by frontend/index.html and serves the
 * frontend from the same origin, avoiding browser CORS configuration.
 */
public final class Main {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault());
    private static final Pattern JSON_VALUE = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*(\\\"(?:\\\\.|[^\\\"])*\\\"|-?\\d+(?:\\.\\d+)?|null)");
    private final Db db;
    private final Path frontend;
    private final Path uploads;

    private Main(Db db, Path frontend) { this.db = db; this.frontend = frontend.toAbsolutePath().normalize(); this.uploads = Path.of(db.uploadsDir()).toAbsolutePath().normalize(); }

    public static void main(String[] args) throws Exception {
        String config = args.length > 0 ? args[0] : "config.properties";
        String staticDir = args.length > 1 ? args[1] : "frontend";
        Main app = new Main(new Db(config), Path.of(staticDir));
        HttpServer server = HttpServer.create(new InetSocketAddress(app.db.port()), 0);
        server.createContext("/api", app::api);
        server.createContext("/", app::staticFile);
        server.setExecutor(null);
        server.start();
        System.out.println("QuickPrint is running at http://localhost:" + app.db.port());
    }

    private void api(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/api/health".equals(path) && "GET".equals(method)) {
                json(exchange, 200, "{\"ok\":true}"); return;
            }
            if ("/api/uploads".equals(path) && "POST".equals(method)) { uploadPdf(exchange); return; }
            Matcher fileRoute = Pattern.compile("^/api/uploads/([a-f0-9]{32}\\.pdf)$").matcher(path);
            if (fileRoute.matches() && "GET".equals(method)) { downloadPdf(exchange, fileRoute.group(1)); return; }
            if ("/api/shop-prices".equals(path) && "GET".equals(method)) { getPrices(exchange); return; }
            if ("/api/shop-prices".equals(path) && "PUT".equals(method)) { updatePrices(exchange); return; }
            if ("/api/shop-orders".equals(path) && "POST".equals(method)) { createOrder(exchange); return; }
            if ("/api/shop-orders".equals(path) && "GET".equals(method)) { listOrders(exchange); return; }
            Matcher route = Pattern.compile("^/api/shop-orders/(\\d+)/status$").matcher(path);
            if (route.matches() && "PATCH".equals(method)) { updateStatus(exchange, Integer.parseInt(route.group(1))); return; }
            error(exchange, 404, "Endpoint not found");
        } catch (IllegalArgumentException e) { error(exchange, 400, e.getMessage());
        } catch (SQLException e) { e.printStackTrace(); error(exchange, 500, "Database error. Check MySQL and config.properties.");
        } catch (Exception e) { e.printStackTrace(); error(exchange, 500, "Unexpected server error"); }
    }

    private void createOrder(HttpExchange exchange) throws Exception {
        String body = body(exchange);
        String name = required(jsonField(body, "studentName"), "Student name is required", 100);
        String phone = required(jsonField(body, "phone"), "A valid phone number is required", 30);
        String encodedItems = required(jsonField(body, "itemsBase64"), "Order items are required", 20000);
        String note = optional(jsonField(body, "notes"), 280);
        String items;
        try { items = new String(Base64.getDecoder().decode(encodedItems), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Order items could not be read"); }
        if (!items.trim().startsWith("[") || !items.trim().endsWith("]") || items.length() > 16000) {
            throw new IllegalArgumentException("Order must contain valid service items");
        }
        String totalValue = jsonField(body, "total");
        double total;
        try { total = Double.parseDouble(totalValue); } catch (Exception e) { throw new IllegalArgumentException("Order total is invalid"); }
        if (total < 0 || total > 100000) throw new IllegalArgumentException("Order total is outside the allowed range");
        int token;
        try (Connection connection = db.connection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO shop_orders (student_name, phone, items_json, total, notes) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name); statement.setString(2, phone); statement.setString(3, items);
            statement.setDouble(4, total); statement.setString(5, note);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No token was generated");
                token = keys.getInt(1);
            }
        }
        json(exchange, 201, "{\"token\":" + token + "}");
    }

    /** Receives one PDF as multipart/form-data and stores it outside the public frontend directory. */
    private void uploadPdf(HttpExchange exchange) throws Exception {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data") || !contentType.contains("boundary=")) {
            throw new IllegalArgumentException("Upload must use multipart/form-data");
        }
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9).replace("\"", "").trim();
        byte[] request = rawBody(exchange, 15 * 1024 * 1024);
        String packet = new String(request, StandardCharsets.ISO_8859_1);
        int headersEnd = packet.indexOf("\r\n\r\n");
        int fileEnd = packet.indexOf("\r\n--" + boundary, headersEnd + 4);
        if (headersEnd < 0 || fileEnd < 0) throw new IllegalArgumentException("Malformed upload request");
        String headers = packet.substring(0, headersEnd);
        Matcher name = Pattern.compile("filename=\\\"([^\\\"]+)\\\"").matcher(headers);
        if (!name.find()) throw new IllegalArgumentException("No PDF file was supplied");
        String original = name.group(1);
        String safeName = original.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (!safeName.toLowerCase(Locale.ROOT).endsWith(".pdf")) throw new IllegalArgumentException("Only PDF files are allowed");
        int start = headersEnd + 4;
        int length = fileEnd - start;
        if (length < 5 || request[start] != '%' || request[start + 1] != 'P' || request[start + 2] != 'D' || request[start + 3] != 'F') {
            throw new IllegalArgumentException("The selected file is not a valid PDF");
        }
        Files.createDirectories(uploads);
        String id = UUID.randomUUID().toString().replace("-", "") + ".pdf";
        Files.write(uploads.resolve(id), java.util.Arrays.copyOfRange(request, start, fileEnd), StandardOpenOption.CREATE_NEW);
        json(exchange, 201, "{\"fileId\":" + quote(id) + ",\"fileName\":" + quote(safeName) + "}");
    }

    private void downloadPdf(HttpExchange exchange, String id) throws IOException {
        Path file = uploads.resolve(id).normalize();
        if (!file.startsWith(uploads) || !Files.isRegularFile(file)) { error(exchange, 404, "Uploaded PDF was not found"); return; }
        byte[] data = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", "application/pdf");
        exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"upload.pdf\"");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    private void getPrices(HttpExchange exchange) throws Exception {
        double bw = 1, color = 8, bwDouble = 1.5, colorDouble = 12;
        try (Connection connection = db.connection(); PreparedStatement statement = connection.prepareStatement("SELECT price_key, amount FROM shop_prices"); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String key = rows.getString("price_key"); double amount = rows.getDouble("amount");
                if ("bw".equals(key)) bw = amount; else if ("color".equals(key)) color = amount;
                else if ("bwDouble".equals(key)) bwDouble = amount; else if ("colorDouble".equals(key)) colorDouble = amount;
            }
        }
        json(exchange, 200, String.format(Locale.US, "{\"bw\":%.2f,\"color\":%.2f,\"bwDouble\":%.2f,\"colorDouble\":%.2f}", bw, color, bwDouble, colorDouble));
    }

    private void updatePrices(HttpExchange exchange) throws Exception {
        String body = body(exchange);
        String[] keys = { "bw", "color", "bwDouble", "colorDouble" };
        double[] amounts = new double[keys.length];
        for (int i = 0; i < keys.length; i++) amounts[i] = price(jsonField(body, keys[i]));
        try (Connection connection = db.connection(); PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO shop_prices (price_key, amount) VALUES (?, ?) ON DUPLICATE KEY UPDATE amount = VALUES(amount)")) {
            for (int i = 0; i < keys.length; i++) { statement.setString(1, keys[i]); statement.setDouble(2, amounts[i]); statement.addBatch(); }
            statement.executeBatch();
        }
        json(exchange, 200, "{\"ok\":true}");
    }

    private void listOrders(HttpExchange exchange) throws Exception {
        String status = query(exchange.getRequestURI(), "status");
        if (status == null || status.isBlank()) status = "ALL";
        if (!"ALL".equals(status) && !validStatus(status)) throw new IllegalArgumentException("Invalid order status");
        String sql = "SELECT token, student_name, phone, items_json, total, notes, status, created_at FROM shop_orders" +
            ("ALL".equals(status) ? "" : " WHERE status = ?") + " ORDER BY token DESC";
        StringBuilder orders = new StringBuilder("[");
        try (Connection connection = db.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!"ALL".equals(status)) statement.setString(1, status);
            try (ResultSet rows = statement.executeQuery()) {
                boolean first = true;
                while (rows.next()) {
                    if (!first) orders.append(','); first = false;
                    String itemJson = rows.getString("items_json");
                    // Schema writes only validated JSON from this server. Graceful fallback protects the response if legacy data is malformed.
                    if (itemJson == null || !itemJson.trim().startsWith("[")) itemJson = "[]";
                    Timestamp created = rows.getTimestamp("created_at");
                    orders.append("{\"token\":").append(rows.getInt("token"))
                        .append(",\"name\":").append(quote(rows.getString("student_name")))
                        .append(",\"phone\":").append(quote(rows.getString("phone")))
                        .append(",\"items\":").append(itemJson)
                        .append(",\"total\":").append(rows.getDouble("total"))
                        .append(",\"note\":").append(quote(rows.getString("notes")))
                        .append(",\"status\":").append(quote(rows.getString("status")))
                        .append(",\"time\":").append(quote(created == null ? "" : TIME.format(created.toInstant())))
                        .append('}');
                }
            }
        }
        json(exchange, 200, "{\"orders\":" + orders.append("]") + "}");
    }

    private void updateStatus(HttpExchange exchange, int token) throws Exception {
        String status = jsonField(body(exchange), "status");
        if (!validStatus(status)) throw new IllegalArgumentException("Status must be PENDING, PRINTING, READY, or COLLECTED");
        int affected;
        try (Connection connection = db.connection(); PreparedStatement statement = connection.prepareStatement("UPDATE shop_orders SET status = ? WHERE token = ?")) {
            statement.setString(1, status); statement.setInt(2, token); affected = statement.executeUpdate();
        }
        if (affected == 0) { error(exchange, 404, "Order token was not found"); return; }
        json(exchange, 200, "{\"ok\":true}");
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) { error(exchange, 405, "Method not allowed"); return; }
        String requested = exchange.getRequestURI().getPath();
        if (requested.equals("/")) requested = "/index.html";
        Path file = frontend.resolve(requested.substring(1)).normalize();
        if (!file.startsWith(frontend) || !Files.isRegularFile(file)) { error(exchange, 404, "File not found"); return; }
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType(file));
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, "HEAD".equals(exchange.getRequestMethod()) ? -1 : bytes.length);
        if (!"HEAD".equals(exchange.getRequestMethod())) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String body(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            if (output.size() > 40000) throw new IllegalArgumentException("Request is too large");
            return output.toString(StandardCharsets.UTF_8);
        }
    }
    private static byte[] rawBody(HttpExchange exchange, int limit) throws IOException {
        try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > limit) throw new IllegalArgumentException("PDF must be 15 MB or smaller");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
    private static String jsonField(String json, String key) {
        Matcher m = JSON_VALUE.matcher(json);
        while (m.find()) if (key.equals(m.group(1))) return decodeJson(m.group(2));
        return null;
    }
    private static String decodeJson(String value) {
        if (value == null || "null".equals(value)) return null;
        if (!value.startsWith("\"")) return value;
        String s = value.substring(1, value.length() - 1);
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }
    private static String required(String value, String message, int max) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
        String cleaned = value.trim(); if (cleaned.length() > max) throw new IllegalArgumentException("One of the fields is too long"); return cleaned;
    }
    private static String optional(String value, int max) { if (value == null) return null; if (value.length() > max) throw new IllegalArgumentException("Notes are too long"); return value.trim(); }
    private static double price(String value) { try { double amount = Double.parseDouble(value); if (amount < 0 || amount > 10000) throw new NumberFormatException(); return amount; } catch (Exception e) { throw new IllegalArgumentException("Each price must be between 0 and 10000"); } }
    private static boolean validStatus(String status) { return "PENDING".equals(status) || "PRINTING".equals(status) || "READY".equals(status) || "COLLECTED".equals(status); }
    private static String query(URI uri, String wanted) {
        if (uri.getRawQuery() == null) return null;
        for (String pair : uri.getRawQuery().split("&")) { String[] parts = pair.split("=", 2); if (parts.length == 2 && wanted.equals(parts[0])) return URLDecoder.decode(parts[1], StandardCharsets.UTF_8); }
        return null;
    }
    private static String quote(String value) { if (value == null) return "null"; return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8"; if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8"; if (name.endsWith(".svg")) return "image/svg+xml"; return "application/octet-stream";
    }
    private static void json(HttpExchange exchange, int status, String body) throws IOException { byte[] data = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(status, data.length); exchange.getResponseBody().write(data); exchange.close(); }
    private static void error(HttpExchange exchange, int status, String message) throws IOException { json(exchange, status, "{\"error\":" + quote(message) + "}"); }
}
