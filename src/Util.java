import java.util.HashMap;
import java.util.Map;

public class Util {
    public static Map<String, String> parseFormData(String formData) throws Exception {
        Map<String, String> map = new HashMap<>();
        if (formData == null || formData.isEmpty()) return map;
        String[] pairs = formData.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(java.net.URLDecoder.decode(kv[0], "UTF-8").trim(), java.net.URLDecoder.decode(kv[1], "UTF-8").trim());
            } else if (kv.length == 1) {
                map.put(java.net.URLDecoder.decode(kv[0], "UTF-8").trim(), "");
            }
        }
        return map;
    }
    
    public static String hashPassword(String pass) {
        if (pass == null) pass = "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(pass.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    // Simple JSON escaper
    public static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
