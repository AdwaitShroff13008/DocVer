import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

public class TeamHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "X-Token");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String token = exchange.getRequestHeaders().getFirst("X-Token");
            Integer userId = token != null ? Sessions.getUserId(token) : null;

            if (userId == null) {
                sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Unauthorized\"}");
                return;
            }

            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("POST".equalsIgnoreCase(method) && "/api/teams/create".equals(path)) {
                handleCreateTeam(exchange, userId);
            } else if ("GET".equalsIgnoreCase(method) && "/api/teams/list".equals(path)) {
                handleListTeams(exchange, userId);
            } else if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/teams/members")) {
                handleListMembers(exchange, userId, exchange.getRequestURI().getQuery());
            } else if ("POST".equalsIgnoreCase(method) && "/api/teams/members/add".equals(path)) {
                handleAddMember(exchange, userId);
            } else if ("POST".equalsIgnoreCase(method) && "/api/teams/members/remove".equals(path)) {
                handleRemoveMember(exchange, userId);
            } else if ("POST".equalsIgnoreCase(method) && "/api/teams/members/permissions".equals(path)) {
                handleUpdatePermissions(exchange, userId);
            } else {
                sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Not found\"}");
            }

        } catch (Exception e) {
            logError(e);
            try { sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Server error: " + Util.escapeJson(e.getMessage()) + "\"}"); } catch(Exception ignored){}
        }
    }

    private void handleCreateTeam(HttpExchange exchange, int userId) throws Exception {
        InputStream is = exchange.getRequestBody();
        String body = new String(is.readAllBytes());
        Map<String, String> data = Util.parseFormData(body);
        String name = data.get("name");

        if (name == null || name.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"success\":false, \"message\":\"Team name required\"}");
            return;
        }

        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int teamId = -1;
                try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO teams (name, created_by) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setString(1, name.trim());
                    stmt.setInt(2, userId);
                    stmt.executeUpdate();
                    try (ResultSet keys = stmt.getGeneratedKeys()) {
                        if (keys.next()) teamId = keys.getInt(1);
                    }
                }

                if (teamId != -1) {
                    try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO team_members (team_id, user_id, can_view, can_update, can_manage) VALUES (?, ?, 1, 1, 1)")) {
                        stmt.setInt(1, teamId);
                        stmt.setInt(2, userId);
                        stmt.executeUpdate();
                    }
                }
                conn.commit();
                sendResponse(exchange, 200, "{\"success\":true, \"team_id\":" + teamId + "}");
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private void handleListTeams(HttpExchange exchange, int userId) throws Exception {
        try (Connection conn = Database.getConnection()) {
            String sql = "SELECT t.id, t.name, tm.can_view, tm.can_update, tm.can_manage, " +
                         "(SELECT COUNT(*) FROM team_members WHERE team_id = t.id) as member_count " +
                         "FROM teams t " +
                         "JOIN team_members tm ON t.id = tm.team_id " +
                         "WHERE tm.user_id = ? ORDER BY t.name ASC";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{")
                          .append("\"id\":").append(rs.getInt("id")).append(",")
                          .append("\"name\":\"").append(Util.escapeJson(rs.getString("name"))).append("\",")
                          .append("\"can_view\":").append(rs.getInt("can_view") == 1).append(",")
                          .append("\"can_update\":").append(rs.getInt("can_update") == 1).append(",")
                          .append("\"can_manage\":").append(rs.getInt("can_manage") == 1).append(",")
                          .append("\"member_count\":").append(rs.getInt("member_count"))
                          .append("}");
                    }
                    sb.append("]");
                    sendResponse(exchange, 200, "{\"success\":true, \"data\":" + sb.toString() + "}");
                }
            }
        }
    }

    private void handleListMembers(HttpExchange exchange, int userId, String query) throws Exception {
        int teamId = Integer.parseInt(getQueryParam(query, "team_id"));
        
        try (Connection conn = Database.getConnection()) {
            // Check if user is in the team
            if (!isUserInTeam(conn, teamId, userId)) {
                sendResponse(exchange, 403, "{\"success\":false, \"message\":\"Forbidden\"}");
                return;
            }

            String sql = "SELECT u.id, u.name, u.email, tm.can_view, tm.can_update, tm.can_manage, tm.joined_at " +
                         "FROM users u " +
                         "JOIN team_members tm ON u.id = tm.user_id " +
                         "WHERE tm.team_id = ? ORDER BY tm.joined_at ASC";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                try (ResultSet rs = stmt.executeQuery()) {
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{")
                          .append("\"id\":").append(rs.getInt("id")).append(",")
                          .append("\"name\":\"").append(Util.escapeJson(rs.getString("name"))).append("\",")
                          .append("\"email\":\"").append(Util.escapeJson(rs.getString("email"))).append("\",")
                          .append("\"can_view\":").append(rs.getInt("can_view") == 1).append(",")
                          .append("\"can_update\":").append(rs.getInt("can_update") == 1).append(",")
                          .append("\"can_manage\":").append(rs.getInt("can_manage") == 1).append(",")
                          .append("\"joined_at\":\"").append(rs.getString("joined_at"))
                          .append("}");
                    }
                    sb.append("]");
                    sendResponse(exchange, 200, "{\"success\":true, \"data\":" + sb.toString() + "}");
                }
            }
        }
    }

    private void handleAddMember(HttpExchange exchange, int userId) throws Exception {
        InputStream is = exchange.getRequestBody();
        Map<String, String> data = Util.parseFormData(new String(is.readAllBytes()));
        int teamId = Integer.parseInt(data.get("team_id"));
        String email = data.get("email");
        boolean canView = "true".equals(data.get("can_view"));
        boolean canUpdate = "true".equals(data.get("can_update"));
        boolean canManage = "true".equals(data.get("can_manage"));

        try (Connection conn = Database.getConnection()) {
            if (!hasAdminAccess(conn, teamId, userId)) {
                sendResponse(exchange, 403, "{\"success\":false, \"message\":\"Manage access required\"}");
                return;
            }

            int targetUserId = -1;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM users WHERE email=?")) {
                stmt.setString(1, email);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) targetUserId = rs.getInt(1);
                }
            }

            if (targetUserId == -1) {
                sendResponse(exchange, 400, "{\"success\":false, \"message\":\"User not found with this email\"}");
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO team_members (team_id, user_id, can_view, can_update, can_manage) VALUES (?, ?, ?, ?, ?)")) {
                stmt.setInt(1, teamId);
                stmt.setInt(2, targetUserId);
                stmt.setInt(3, canView ? 1 : 0);
                stmt.setInt(4, canUpdate ? 1 : 0);
                stmt.setInt(5, canManage ? 1 : 0);
                stmt.executeUpdate();
                sendResponse(exchange, 200, "{\"success\":true}");
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"success\":false, \"message\":\"User may already be in the team\"}");
            }
        }
    }

    private void handleRemoveMember(HttpExchange exchange, int userId) throws Exception {
        InputStream is = exchange.getRequestBody();
        Map<String, String> data = Util.parseFormData(new String(is.readAllBytes()));
        int teamId = Integer.parseInt(data.get("team_id"));
        int targetUserId = Integer.parseInt(data.get("user_id"));

        try (Connection conn = Database.getConnection()) {
            if (!hasAdminAccess(conn, teamId, userId) && targetUserId != userId) {
                sendResponse(exchange, 403, "{\"success\":false, \"message\":\"Admin access required\"}");
                return;
            }

            // Cannot remove the only admin (ideally), but for simplicity we allow if they want.
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM team_members WHERE team_id=? AND user_id=?")) {
                stmt.setInt(1, teamId);
                stmt.setInt(2, targetUserId);
                stmt.executeUpdate();
            }
            sendResponse(exchange, 200, "{\"success\":true}");
        }
    }

    private void handleUpdatePermissions(HttpExchange exchange, int userId) throws Exception {
        InputStream is = exchange.getRequestBody();
        Map<String, String> data = Util.parseFormData(new String(is.readAllBytes()));
        int teamId = Integer.parseInt(data.get("team_id"));
        int targetUserId = Integer.parseInt(data.get("user_id"));
        boolean canView = "true".equals(data.get("can_view"));
        boolean canUpdate = "true".equals(data.get("can_update"));
        boolean canManage = "true".equals(data.get("can_manage"));

        try (Connection conn = Database.getConnection()) {
            if (!hasAdminAccess(conn, teamId, userId)) {
                sendResponse(exchange, 403, "{\"success\":false, \"message\":\"Manage access required\"}");
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement("UPDATE team_members SET can_view=?, can_update=?, can_manage=? WHERE team_id=? AND user_id=?")) {
                stmt.setInt(1, canView ? 1 : 0);
                stmt.setInt(2, canUpdate ? 1 : 0);
                stmt.setInt(3, canManage ? 1 : 0);
                stmt.setInt(4, teamId);
                stmt.setInt(5, targetUserId);
                stmt.executeUpdate();
            }
            sendResponse(exchange, 200, "{\"success\":true}");
        }
    }

    public static boolean isUserInTeam(Connection conn, int teamId, int userId) throws Exception {
        try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM team_members WHERE team_id=? AND user_id=?")) {
            check.setInt(1, teamId); check.setInt(2, userId);
            try (ResultSet rs = check.executeQuery()) { return rs.next(); }
        }
    }

    public static boolean hasAdminAccess(Connection conn, int teamId, int userId) throws Exception {
        try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM team_members WHERE team_id=? AND user_id=? AND can_manage=1")) {
            check.setInt(1, teamId); check.setInt(2, userId);
            try (ResultSet rs = check.executeQuery()) { return rs.next(); }
        }
    }

    private String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2 && kv[0].equals(key)) {
                return kv[1];
            }
        }
        return null;
    }

    private void logError(Exception e) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            java.nio.file.Files.writeString(new java.io.File("error.log").toPath(), 
                "[" + java.time.LocalDateTime.now() + "] TeamHandler: " + sw.toString() + "\n", 
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch(Exception ignored){}
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws Exception {
        byte[] resBytes = response.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", statusCode == 200 ? "application/json" : "text/plain");
        exchange.sendResponseHeaders(statusCode, resBytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(resBytes); }
    }
}
