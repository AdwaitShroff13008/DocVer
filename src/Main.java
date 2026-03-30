import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        // Ensure necessary folders exist
        new File("data").mkdirs();
        new File("uploads").mkdirs();

        // Initialize SQLite schema
        Database.initialize();

        // Set up server on port 8080
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
        // Static file handler for frontend UI
        server.createContext("/", new StaticFileHandler());
        
        // API Handlers for JSON backend communication
        server.createContext("/api/auth", new AuthHandler());
        server.createContext("/api/docs", new DocHandler());
        server.createContext("/api/teams", new TeamHandler());

        server.setExecutor(null); // creates a default thread executor
        server.start();
        System.out.println("DocVerControl Server started! Please go to http://localhost:8080/");
    }
}
