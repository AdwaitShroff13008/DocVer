import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

public class DocHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "X-Token, X-File-Name, X-Notes, X-Document-Id");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String token = exchange.getRequestHeaders().getFirst("X-Token");
            Integer userId = token != null ? Sessions.getUserId(token) : null;
            
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            
            // Allow download auth via query param
            if (userId == null && query != null && query.contains("token=")) {
                String qToken = getQueryParam(query, "token");
                userId = Sessions.getUserId(qToken);
            }

            if (userId == null) {
                sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Unauthorized\"}");
                return;
            }

            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method) && "/api/docs/list".equals(path)) {
                handleList(exchange, userId);
            } else if ("GET".equalsIgnoreCase(method) && "/api/docs/dashboard".equals(path)) {
                handleDashboard(exchange, userId);
            } else if ("GET".equalsIgnoreCase(method) && "/api/docs/history".equals(path)) {
                handleHistory(exchange, userId, query);
            } else if ("GET".equalsIgnoreCase(method) && "/api/docs/download".equals(path)) {
                handleDownload(exchange, userId, query);
            } else if ("POST".equalsIgnoreCase(method) && "/api/docs/upload".equals(path)) {
                handleUpload(exchange, userId);
            } else if ("POST".equalsIgnoreCase(method) && "/api/docs/rename".equals(path)) {
                handleRename(exchange, userId);
            } else if ("POST".equalsIgnoreCase(method) && "/api/docs/delete".equals(path)) {
                handleDelete(exchange, userId);
            } else if ("POST".equalsIgnoreCase(method) && "/api/docs/share".equals(path)) {
                handleShare(exchange, userId);
            } else {
                sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Not found\"}");
            }

        } catch (Exception e) {
            logError(e);
            try { sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Server error: " + Util.escapeJson(e.getMessage()) + "\"}"); } catch(Exception ignored){}
        }
    }

    private void handleList(HttpExchange exchange, int userId) throws Exception {
        try (Connection conn = Database.getConnection()) {
            String sql = "SELECT d.id, d.name, d.created_at, " +
                "(SELECT id FROM versions WHERE document_id = d.id ORDER BY version_num DESC LIMIT 1) as last_vid, " +
                "(SELECT COUNT(*) FROM versions WHERE document_id = d.id) as v_cnt, 0 as is_shared, 1 as can_update " +
                "FROM documents d WHERE d.user_id = ? " +
                "UNION " +
                "SELECT d.id, d.name, sd.shared_at as created_at, " +
                "(SELECT id FROM versions WHERE document_id = d.id ORDER BY version_num DESC LIMIT 1) as last_vid, " +
                "(SELECT COUNT(*) FROM versions WHERE document_id = d.id) as v_cnt, 1 as is_shared, tm.can_update " +
                "FROM documents d " +
                "JOIN shared_documents sd ON d.id = sd.document_id " +
                "JOIN team_members tm ON sd.team_id = tm.team_id " +
                "WHERE tm.user_id = ? AND tm.can_view = 1 " +
                "ORDER BY created_at DESC";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, userId);
                stmt.setInt(2, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{")
                          .append("\"id\":").append(rs.getInt("id")).append(",")
                          .append("\"name\":\"").append(Util.escapeJson(rs.getString("name"))).append("\",")
                          .append("\"created_at\":\"").append(rs.getString("created_at")).append("\",")
                          .append("\"is_shared\":").append(rs.getInt("is_shared") == 1).append(",")
                          .append("\"can_update\":").append(rs.getInt("can_update") == 1).append(",")
                          .append("\"version_count\":").append(rs.getInt("v_cnt")).append(",")
                          .append("\"last_vid\":").append(rs.getInt("last_vid"))
                          .append("}");
                    }
                    sb.append("]");
                    sendResponse(exchange, 200, "{\"success\":true, \"data\":" + sb.toString() + "}");
                }
            }
        }
    }

    private void handleHistory(HttpExchange exchange, int userId, String query) throws Exception {
        int docId = Integer.parseInt(getQueryParam(query, "id"));
        try (Connection conn = Database.getConnection()) {
            // Check view access
            if (!hasViewAccess(conn, docId, userId)) {
                sendResponse(exchange, 403, "{\"success\":false}"); return;
            }
            
            try (PreparedStatement stmt = conn.prepareStatement("SELECT id, version_num, file_size, notes, uploaded_at FROM versions WHERE document_id=? ORDER BY version_num DESC")) {
                stmt.setInt(1, docId);
                try (ResultSet rs = stmt.executeQuery()) {
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{")
                          .append("\"id\":").append(rs.getInt("id")).append(",")
                          .append("\"version_num\":").append(rs.getInt("version_num")).append(",")
                          .append("\"file_size\":").append(rs.getLong("file_size")).append(",")
                          .append("\"notes\":\"").append(Util.escapeJson(rs.getString("notes"))).append("\",")
                          .append("\"uploaded_at\":\"").append(rs.getString("uploaded_at")).append("\"")
                          .append("}");
                    }
                    sb.append("]");
                    sendResponse(exchange, 200, "{\"success\":true, \"data\":" + sb.toString() + "}");
                }
            }
        }
    }

    private void handleDashboard(HttpExchange exchange, int userId) throws Exception {
        try (Connection conn = Database.getConnection()) {
            String userName = "", userEmail = "";
            try (PreparedStatement uStmt = conn.prepareStatement("SELECT name, email FROM users WHERE id=?")) {
                uStmt.setInt(1, userId);
                try (ResultSet ur = uStmt.executeQuery()) {
                    if (ur.next()) {
                        userName = ur.getString("name");
                        userEmail = ur.getString("email");
                    }
                }
            }
            
            int totalDocs = 0, totalVers = 0;
            try (PreparedStatement dStmt = conn.prepareStatement("SELECT COUNT(*) FROM documents WHERE user_id=?")) {
                dStmt.setInt(1, userId);
                try (ResultSet dr = dStmt.executeQuery()) {
                    if (dr.next()) totalDocs = dr.getInt(1);
                }
            }
            
            try (PreparedStatement vStmt = conn.prepareStatement("SELECT COUNT(*) FROM versions v JOIN documents d ON v.document_id = d.id WHERE d.user_id=?")) {
                vStmt.setInt(1, userId);
                try (ResultSet vr = vStmt.executeQuery()) {
                    if (vr.next()) totalVers = vr.getInt(1);
                }
            }
            
            StringBuilder ab = new StringBuilder("[");
            // Recent activity
            try (PreparedStatement aStmt = conn.prepareStatement("SELECT action, timestamp FROM activity_logs WHERE user_id=? ORDER BY timestamp DESC LIMIT 10")) {
                aStmt.setInt(1, userId);
                try (ResultSet ar = aStmt.executeQuery()) {
                    boolean first = true;
                    while (ar.next()) {
                        if (!first) ab.append(",");
                        first = false;
                        ab.append("{")
                          .append("\"action\":\"").append(Util.escapeJson(ar.getString("action"))).append("\",")
                          .append("\"timestamp\":\"").append(ar.getString("timestamp")).append("\"")
                          .append("}");
                    }
                }
            }
            ab.append("]");
            
            String userJson = "{\"name\":\"" + Util.escapeJson(userName) + "\", \"email\":\"" + Util.escapeJson(userEmail) + "\"}";
            String res = "{\"success\":true, \"user\":" + userJson + ", \"totalDocs\":" + totalDocs + ", \"totalVersions\":" + totalVers + ", \"activity\":" + ab.toString() + "}";
            sendResponse(exchange, 200, res);
        }
    }

    private void handleUpload(HttpExchange exchange, int userId) throws Exception {
        String docIdStr = exchange.getRequestHeaders().getFirst("X-Document-Id");
        String fileName = Util.escapeJson(java.net.URLDecoder.decode(exchange.getRequestHeaders().getFirst("X-File-Name"), "UTF-8"));
        String notesRaw = exchange.getRequestHeaders().getFirst("X-Notes");
        String notes = notesRaw != null ? Util.escapeJson(java.net.URLDecoder.decode(notesRaw, "UTF-8")) : "";
        if (fileName == null || fileName.isEmpty()) fileName = "Untitled";

        int docId = -1;
        int nextVersion = 1;

        try (Connection conn = Database.getConnection()) {
            if (docIdStr != null && !docIdStr.isEmpty()) {
                docId = Integer.parseInt(docIdStr);
                if (!hasUpdateAccess(conn, docId, userId)) { sendResponse(exchange, 403, "{\"success\":false, \"message\":\"No update permission\"}"); return; }
                try (PreparedStatement check = conn.prepareStatement("SELECT name FROM documents WHERE id=?")) {
                    check.setInt(1, docId);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            fileName = rs.getString("name");
                        }
                    }
                }
                
                try (PreparedStatement vCheck = conn.prepareStatement("SELECT MAX(version_num) FROM versions WHERE document_id=?")) {
                    vCheck.setInt(1, docId);
                    try (ResultSet vRs = vCheck.executeQuery()) {
                        if (vRs.next()) { nextVersion = vRs.getInt(1) + 1; }
                    }
                }
            } else {
                try (PreparedStatement insertDoc = conn.prepareStatement("INSERT INTO documents (user_id, name) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    insertDoc.setInt(1, userId);
                    insertDoc.setString(2, fileName);
                    insertDoc.executeUpdate();
                    try (ResultSet keys = insertDoc.getGeneratedKeys()) {
                        if (keys.next()) docId = keys.getInt(1);
                    }
                }
            }
            
            File uploadDir = new File("uploads/" + userId);
            uploadDir.mkdirs();
            String savedFileName = UUID.randomUUID().toString() + "_" + fileName.replaceAll("[^a-zA-Z0-9.-]", "_");
            File targetFile = new File(uploadDir, savedFileName);
            
            InputStream is = exchange.getRequestBody();
            long size = 0;
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = is.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                    size += read;
                }
            }
            
            try (PreparedStatement insV = conn.prepareStatement("INSERT INTO versions (document_id, version_num, file_path, file_size, notes) VALUES (?, ?, ?, ?, ?)")) {
                insV.setInt(1, docId);
                insV.setInt(2, nextVersion);
                insV.setString(3, targetFile.getAbsolutePath());
                insV.setLong(4, size);
                insV.setString(5, notes.isEmpty() ? "Revision " + nextVersion : notes);
                insV.executeUpdate();
            }
            
            logActivity(conn, userId, "Uploaded version v" + nextVersion + " of document '" + fileName + "'");
            sendResponse(exchange, 200, "{\"success\": true, \"docId\": " + docId + "}");
        }
    }

    private void handleRename(HttpExchange exchange, int userId) throws Exception {
        InputStream is = exchange.getRequestBody();
        String body = new String(is.readAllBytes());
        Map<String, String> data = Util.parseFormData(body);
        int docId = Integer.parseInt(data.get("id"));
        String newName = data.get("name");
        
        try (Connection conn = Database.getConnection()) {
            String oldName = null;
            try (PreparedStatement get = conn.prepareStatement("SELECT name FROM documents WHERE id=? AND user_id=?")) {
                get.setInt(1, docId); get.setInt(2, userId);
                try (ResultSet rs = get.executeQuery()) {
                    if (rs.next()) oldName = rs.getString("name");
                }
            }
            
            if (oldName != null) {
                try (PreparedStatement upd = conn.prepareStatement("UPDATE documents SET name=? WHERE id=?")) {
                    upd.setString(1, newName);
                    upd.setInt(2, docId);
                    upd.executeUpdate();
                }
                logActivity(conn, userId, "Renamed document '" + oldName + "' to '" + newName + "'");
                sendResponse(exchange, 200, "{\"success\":true}");
            } else {
                sendResponse(exchange, 403, "{\"success\":false}");
            }
        }
    }

    private void handleDelete(HttpExchange exchange, int userId) throws Exception {
        InputStream is = exchange.getRequestBody();
        String body = new String(is.readAllBytes());
        Map<String, String> data = Util.parseFormData(body);
        int docId = Integer.parseInt(data.get("id"));
        
        try (Connection conn = Database.getConnection()) {
            String docName = null;
            try (PreparedStatement get = conn.prepareStatement("SELECT name FROM documents WHERE id=? AND user_id=?")) {
                get.setInt(1, docId); get.setInt(2, userId);
                try (ResultSet rs = get.executeQuery()) {
                    if (rs.next()) docName = rs.getString("name");
                }
            }
            
            if (docName != null) {
                // Get paths to delete physical files
                try (PreparedStatement paths = conn.prepareStatement("SELECT file_path FROM versions WHERE document_id=?")) {
                    paths.setInt(1, docId);
                    try (ResultSet pr = paths.executeQuery()) {
                        while(pr.next()) {
                            new File(pr.getString("file_path")).delete();
                        }
                    }
                }
                
                try (PreparedStatement delV = conn.prepareStatement("DELETE FROM versions WHERE document_id=?")) {
                    delV.setInt(1, docId); delV.executeUpdate();
                }
                
                try (PreparedStatement delD = conn.prepareStatement("DELETE FROM documents WHERE id=?")) {
                    delD.setInt(1, docId); delD.executeUpdate();
                }
                
                logActivity(conn, userId, "Deleted document '" + docName + "'");
                sendResponse(exchange, 200, "{\"success\":true}");
            } else {
                sendResponse(exchange, 403, "{\"success\":false}");
            }
        }
    }

    private void handleDownload(HttpExchange exchange, int userId, String query) throws Exception {
        String vidStr = getQueryParam(query, "vid");
        if (vidStr == null) { sendResponse(exchange, 400, "{\"success\":false}"); return; }
        
        try (Connection conn = Database.getConnection()) {
            int docId = -1;
            try (PreparedStatement q = conn.prepareStatement("SELECT document_id FROM versions WHERE id=?")) {
                 q.setInt(1, Integer.parseInt(vidStr));
                 try (ResultSet rs = q.executeQuery()) { if (rs.next()) docId = rs.getInt(1); }
            }
            if (docId == -1 || !hasViewAccess(conn, docId, userId)) {
                sendResponse(exchange, 403, "Unauthorized or not found"); return;
            }
            try (PreparedStatement check = conn.prepareStatement("SELECT v.file_path, d.name, v.version_num FROM versions v JOIN documents d ON v.document_id = d.id WHERE v.id = ?")) {
                check.setInt(1, Integer.parseInt(vidStr));
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        File file = new File(rs.getString("file_path"));
                        if (!file.exists()) {
                            sendResponse(exchange, 404, "File missing on server");
                            return;
                        }
                        String dlName = rs.getString("name");
                        if (!dlName.contains(".")) dlName += ".dat";
                        
                        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + dlName + "\"");
                        exchange.sendResponseHeaders(200, file.length());
                        
                        try (OutputStream os = exchange.getResponseBody();
                             FileInputStream fs = new FileInputStream(file)) {
                            final byte[] buffer = new byte[65536];
                            int count;
                            while ((count = fs.read(buffer)) >= 0) {
                                os.write(buffer, 0, count);
                            }
                        }
                        return; // Done
                    }
                }
            }
            sendResponse(exchange, 403, "Unauthorized or not found");
        }
    }

    private void handleShare(HttpExchange exchange, int userId) throws Exception {
        InputStream is = exchange.getRequestBody();
        Map<String, String> data = Util.parseFormData(new String(is.readAllBytes()));
        int docId = Integer.parseInt(data.get("id"));
        int teamId = Integer.parseInt(data.get("team_id"));

        try (Connection conn = Database.getConnection()) {
            try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM documents WHERE id=? AND user_id=?")) {
                check.setInt(1, docId); check.setInt(2, userId);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) { sendResponse(exchange, 403, "{\"success\":false, \"message\":\"Not owner\"}"); return; }
                }
            }
            
            if (!TeamHandler.isUserInTeam(conn, teamId, userId)) {
                sendResponse(exchange, 403, "{\"success\":false, \"message\":\"Not in team\"}"); return;
            }

            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO shared_documents (document_id, team_id, shared_by) VALUES (?, ?, ?)")) {
                stmt.setInt(1, docId); stmt.setInt(2, teamId); stmt.setInt(3, userId);
                stmt.executeUpdate();
                logActivity(conn, userId, "Shared document " + docId + " with team " + teamId);
                sendResponse(exchange, 200, "{\"success\":true}");
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"success\":false, \"message\":\"Already shared\"}");
            }
        }
    }

    private boolean hasViewAccess(Connection conn, int docId, int userId) throws Exception {
        try (PreparedStatement test = conn.prepareStatement(
                "SELECT 1 FROM documents WHERE id = ? AND user_id = ? " +
                "UNION " +
                "SELECT 1 FROM shared_documents sd JOIN team_members tm ON sd.team_id = tm.team_id " +
                "WHERE sd.document_id = ? AND tm.user_id = ? AND tm.can_view = 1")) {
            test.setInt(1, docId); test.setInt(2, userId);
            test.setInt(3, docId); test.setInt(4, userId);
            try (ResultSet rs = test.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasUpdateAccess(Connection conn, int docId, int userId) throws Exception {
        try (PreparedStatement test = conn.prepareStatement(
                "SELECT 1 FROM documents WHERE id = ? AND user_id = ? " +
                "UNION " +
                "SELECT 1 FROM shared_documents sd JOIN team_members tm ON sd.team_id = tm.team_id " +
                "WHERE sd.document_id = ? AND tm.user_id = ? AND tm.can_update = 1")) {
            test.setInt(1, docId); test.setInt(2, userId);
            test.setInt(3, docId); test.setInt(4, userId);
            try (ResultSet rs = test.executeQuery()) {
                return rs.next();
            }
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

    private void logActivity(Connection conn, int userId, String action) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO activity_logs (user_id, action) VALUES (?, ?)")) {
            stmt.setInt(1, userId);
            stmt.setString(2, action);
            stmt.executeUpdate();
        }
    }

    private void logError(Exception e) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            java.nio.file.Files.writeString(new java.io.File("error.log").toPath(), 
                "[" + java.time.LocalDateTime.now() + "] DocHandler: " + sw.toString() + "\n", 
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch(Exception ignored){}
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws Exception {
        byte[] resBytes = response.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", statusCode == 200 ? "application/json" : "text/plain");
        exchange.sendResponseHeaders(statusCode, resBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resBytes);
        }
    }
}
