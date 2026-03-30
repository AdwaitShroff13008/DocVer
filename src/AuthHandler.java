import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

public class AuthHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) {
        try {
            // Enable CORS headers just in case
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes());
                Map<String, String> data = Util.parseFormData(body);
                
                String responseJson = "{\"success\": false, \"message\": \"Unknown endpoint\"}";
                
                if ("/api/auth/register".equals(path)) {
                    String name = data.get("name");
                    String email = data.get("email");
                    String username = data.get("username");
                    String password = data.get("password");
                    
                    try (Connection conn = Database.getConnection()) {
                        // Check if username exists
                        try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM users WHERE username = ?")) {
                            check.setString(1, username);
                            try (ResultSet rs = check.executeQuery()) {
                                if (rs.next()) {
                                    sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Username already taken\"}");
                                    return;
                                }
                            }
                        }

                        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (name, email, username, password_hash) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                            stmt.setString(1, name);
                            stmt.setString(2, email);
                            stmt.setString(3, username);
                            stmt.setString(4, Util.hashPassword(password != null ? password : ""));
                            stmt.executeUpdate();
                            
                            try (ResultSet rs = stmt.getGeneratedKeys()) {
                                if (rs.next()) {
                                    int userId = rs.getInt(1);
                                    String token = Sessions.createSession(userId);
                                    responseJson = "{\"success\": true, \"token\": \"" + token + "\", \"name\": \"" + Util.escapeJson(name) + "\", \"username\": \"" + Util.escapeJson(username) + "\"}";
                                } else {
                                    responseJson = "{\"success\": true}";
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        responseJson = "{\"success\": false, \"message\": \"Email or Username already exists\"}";
                    }
                } else if ("/api/auth/login".equals(path)) {
                    String email = data.get("email");
                    String password = data.get("password");
                    
                    try (Connection conn = Database.getConnection()) {
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT id, name, username FROM users WHERE email = ? AND password_hash = ?")) {
                            stmt.setString(1, email);
                            stmt.setString(2, Util.hashPassword(password));
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) {
                                    int userId = rs.getInt("id");
                                    String name = rs.getString("name");
                                    String username = rs.getString("username");
                                    String token = Sessions.createSession(userId);
                                    responseJson = "{\"success\": true, \"token\": \"" + token + "\", \"name\": \"" + Util.escapeJson(name) + "\", \"username\": \"" + Util.escapeJson(username) + "\"}";
                                } else {
                                    responseJson = "{\"success\": false, \"message\": \"Invalid credentials\"}";
                                }
                            }
                        }
                    }
                } else if ("/api/auth/logout".equals(path)) {
                    String token = exchange.getRequestHeaders().getFirst("X-Token");
                    if (token != null) Sessions.removeSession(token);
                    responseJson = "{\"success\": true}";
                } else if ("/api/auth/update-username".equals(path)) {
                    String token = exchange.getRequestHeaders().getFirst("X-Token");
                    Integer userId = Sessions.getUserId(token);
                    String newUsername = data.get("username");
                    
                    if (userId == null) {
                        sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Unauthorized\"}");
                        return;
                    }
                    if (newUsername == null || newUsername.trim().isEmpty()) {
                        sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Username required\"}");
                        return;
                    }

                    try (Connection conn = Database.getConnection()) {
                        // Check if new username is taken
                        try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM users WHERE username = ? AND id != ?")) {
                            check.setString(1, newUsername);
                            check.setInt(2, userId);
                            try (ResultSet rs = check.executeQuery()) {
                                if (rs.next()) {
                                    sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Username already taken\"}");
                                    return;
                                }
                            }
                        }

                        try (PreparedStatement stmt = conn.prepareStatement("UPDATE users SET username = ? WHERE id = ?")) {
                            stmt.setString(1, newUsername);
                            stmt.setInt(2, userId);
                            stmt.executeUpdate();
                            responseJson = "{\"success\": true, \"username\": \"" + Util.escapeJson(newUsername) + "\"}";
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        responseJson = "{\"success\": false, \"message\": \"Database error\"}";
                    }
                }
                
                sendResponse(exchange, 200, responseJson);
            } else {
                sendResponse(exchange, 405, "{\"success\": false, \"message\": \"Method not allowed\"}");
            }
        } catch (Exception e) {
            logError(e);
            try { sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Server error\"}"); } catch(Exception ignored){}
        }
    }

    private void logError(Exception e) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            java.nio.file.Files.writeString(new java.io.File("error.log").toPath(), 
                "[" + java.time.LocalDateTime.now() + "] AuthHandler: " + sw.toString() + "\n", 
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch(Exception ignored){}
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws Exception {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] resBytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, resBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resBytes);
        }
    }
}
