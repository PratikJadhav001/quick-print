import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Small JDBC helper.
 * Environment variables (DB_URL, DB_USER, DB_PASSWORD, PORT, UPLOADS_DIR) override
 * config.properties, so the same code runs locally and on Render.
 */
public final class Db {
    private final Properties config = new Properties();

    public Db(String configPath) throws IOException, ClassNotFoundException {
        if (Files.isRegularFile(Path.of(configPath))) {
            try (FileInputStream input = new FileInputStream(configPath)) {
                config.load(input);
            }
        }
        Class.forName("com.mysql.cj.jdbc.Driver");
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(
            required("db.url", "DB_URL"), required("db.user", "DB_USER"), setting("db.password", "DB_PASSWORD", ""));
    }

    public int port() {
        return Integer.parseInt(setting("server.port", "PORT", "8080"));
    }

    public String uploadsDir() {
        return setting("uploads.dir", "UPLOADS_DIR", "uploads").trim();
    }

    private String setting(String key, String envName, String fallback) {
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) return env;
        return config.getProperty(key, fallback);
    }

    private String required(String key, String envName) {
        String value = setting(key, envName, null);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException("Missing " + key + " (or env " + envName + ")");
        return value.trim();
    }
}
