import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * FND-05 only: create a new isolated MySQL schema or inspect its Flyway state.
 * Run with the MySQL JDBC driver on the classpath. Credentials come from env.
 */
class IsolatedDb {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("create") || args[0].equals("inspect"))) {
            throw new IllegalArgumentException("usage: IsolatedDb.java create|inspect");
        }
        String schema = required("FND05_DB_NAME");
        if (!(schema.matches("xinghui_fnd05_[a-z0-9_]+") || schema.equals("xinghuiadmintest"))) {
            throw new IllegalArgumentException("FND05_DB_NAME must be an approved isolated schema");
        }
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String url = "jdbc:mysql://" + host + ":" + port
                + "/?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
        try (Connection connection = DriverManager.getConnection(url, required("DB_USERNAME"), required("DB_PASSWORD"));
             Statement statement = connection.createStatement()) {
            boolean exists;
            try (ResultSet result = statement.executeQuery("SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = '" + schema + "'")) {
                exists = result.next();
            }
            if (args[0].equals("create")) {
                if (exists) throw new IllegalStateException("schema already exists: " + schema);
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                System.out.println("created empty schema: " + schema);
                return;
            }
            if (!exists) throw new IllegalStateException("schema does not exist: " + schema);
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '" + schema + "'")) {
                result.next();
                System.out.println("table count: " + result.getLong(1));
            }
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '" + schema + "' AND TABLE_NAME = 'flyway_schema_history'")) {
                result.next();
                if (result.getLong(1) == 0) {
                    System.out.println("Flyway history: absent");
                    return;
                }
            }
            try (ResultSet result = statement.executeQuery("SELECT version, success FROM `" + schema + "`.flyway_schema_history ORDER BY installed_rank")) {
                while (result.next()) {
                    System.out.println("Flyway " + result.getString(1) + " success=" + result.getBoolean(2));
                }
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
