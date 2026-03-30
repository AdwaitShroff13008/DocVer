import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

public class Database {
    private static final String URL = "jdbc:sqlite:data/docver.db";
    private static final Properties props = new Properties();
    
    static {
        props.setProperty("journal_mode", "WAL");
        props.setProperty("busy_timeout", "10000");
    }

    public static void initialize() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL," +
                    "email TEXT UNIQUE NOT NULL," +
                    "username TEXT UNIQUE," +
                    "password_hash TEXT NOT NULL" +
                    ");");

            // Migration: Add username if not exists
            try { stmt.execute("ALTER TABLE users ADD COLUMN username TEXT;"); } catch(Exception ignored){}
            try { stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username ON users(username);"); } catch(Exception ignored){}

            // Documents table: logical document grouping
            stmt.execute("CREATE TABLE IF NOT EXISTS documents (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "user_id INTEGER NOT NULL," +
                    "name TEXT NOT NULL," +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(user_id) REFERENCES users(id)" +
                    ");");

            // Versions table: physical versions of a document
            stmt.execute("CREATE TABLE IF NOT EXISTS versions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "document_id INTEGER NOT NULL," +
                    "version_num INTEGER NOT NULL," +
                    "file_path TEXT NOT NULL," +
                    "file_size INTEGER NOT NULL," +
                    "notes TEXT," +
                    "uploaded_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(document_id) REFERENCES documents(id)" +
                    ");");

            // Activity Logs table: simple tracking
            stmt.execute("CREATE TABLE IF NOT EXISTS activity_logs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "user_id INTEGER NOT NULL," +
                    "action TEXT NOT NULL," +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(user_id) REFERENCES users(id)" +
                    ");");

            // Teams table
            stmt.execute("CREATE TABLE IF NOT EXISTS teams (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL," +
                    "created_by INTEGER NOT NULL," +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(created_by) REFERENCES users(id)" +
                    ");");

            // Team Members table
            stmt.execute("CREATE TABLE IF NOT EXISTS team_members (" +
                    "team_id INTEGER NOT NULL," +
                    "user_id INTEGER NOT NULL," +
                    "can_view BOOLEAN DEFAULT 1," +
                    "can_update BOOLEAN DEFAULT 0," +
                    "can_manage BOOLEAN DEFAULT 0," +
                    "joined_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (team_id, user_id)," +
                    "FOREIGN KEY(team_id) REFERENCES teams(id)," +
                    "FOREIGN KEY(user_id) REFERENCES users(id)" +
                    ");");

            // Migration: Add granular permissions if not exists
            try { stmt.execute("ALTER TABLE team_members ADD COLUMN can_view BOOLEAN DEFAULT 1;"); } catch(Exception ignored){}
            try { stmt.execute("ALTER TABLE team_members ADD COLUMN can_update BOOLEAN DEFAULT 0;"); } catch(Exception ignored){}
            try { stmt.execute("ALTER TABLE team_members ADD COLUMN can_manage BOOLEAN DEFAULT 0;"); } catch(Exception ignored){}

            // Shared Documents junction table
            stmt.execute("CREATE TABLE IF NOT EXISTS shared_documents (" +
                    "document_id INTEGER NOT NULL," +
                    "team_id INTEGER NOT NULL," +
                    "shared_by INTEGER NOT NULL," +
                    "shared_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (document_id, team_id)," +
                    "FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(team_id) REFERENCES teams(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(shared_by) REFERENCES users(id)" +
                    ");");

            System.out.println("Database initialized successfully.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Connection getConnection() throws Exception {
        return DriverManager.getConnection(URL, props);
    }
}
