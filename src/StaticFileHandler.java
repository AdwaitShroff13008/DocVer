import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;

public class StaticFileHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        
        File file = new File("www" + path);
        
        if (file.exists() && !file.isDirectory()) {
            String mimeType = Files.probeContentType(file.toPath());
            if (mimeType == null) {
                if (path.endsWith(".css")) mimeType = "text/css";
                else if (path.endsWith(".js")) mimeType = "application/javascript";
                else if (path.endsWith(".html")) mimeType = "text/html";
                else mimeType = "application/octet-stream";
            }
            
            exchange.getResponseHeaders().set("Content-Type", mimeType);
            exchange.sendResponseHeaders(200, file.length());
            
            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fs = new FileInputStream(file)) {
                final byte[] buffer = new byte[0x10000];
                int count;
                while ((count = fs.read(buffer)) >= 0) {
                    os.write(buffer, 0, count);
                }
            }
        } else {
            String error = "404 Not Found";
            exchange.sendResponseHeaders(404, error.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(error.getBytes());
            }
        }
    }
}
