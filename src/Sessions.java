import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Sessions {
    private static Map<String, Integer> activeSessions = new HashMap<>();

    public static String createSession(int userId) {
        String token = UUID.randomUUID().toString();
        activeSessions.put(token, userId);
        return token;
    }

    public static Integer getUserId(String token) {
        return activeSessions.get(token);
    }
    
    public static void removeSession(String token) {
        activeSessions.remove(token);
    }
}
