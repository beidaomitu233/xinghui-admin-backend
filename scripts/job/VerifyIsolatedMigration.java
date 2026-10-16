import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Read-only JOB-01 verification, pinned to the approved isolated test schema. */
class VerifyIsolatedMigration {
    public static void main(String[] args) throws Exception {
        String schema = System.getenv("DB_NAME");
        if (!"xinghuiadmintest".equals(schema)) {
            throw new IllegalArgumentException("DB_NAME must be xinghuiadmintest");
        }
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
        try (Connection connection = DriverManager.getConnection(url,
                required("DB_USERNAME"), required("DB_PASSWORD"));
                Statement statement = connection.createStatement()) {
            int applied = count(statement, "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version = '20260926.30' AND success = 1");
            int invalid = count(statement, "SELECT COUNT(*) FROM sys_job "
                    + "WHERE status <> '1' OR handler_key IS NOT NULL");
            int preserved = count(statement, "SELECT COUNT(*) FROM sys_job WHERE "
                    + "(job_id = 1 AND invoke_target = 'dbBackupTask.execute') OR "
                    + "(job_id = 2 AND invoke_target = 'aiHealthTask.check')");
            if (applied != 1 || invalid != 0 || preserved != 2) {
                throw new IllegalStateException("JOB-01 migration verification failed: applied=" + applied
                        + ", invalid=" + invalid + ", preserved=" + preserved);
            }
            System.out.println("JOB-01 isolated migration verified: V30 applied; all legacy jobs paused and unbound; 2 seed targets preserved");
        }
    }

    private static int count(Statement statement, String query) throws Exception {
        try (ResultSet result = statement.executeQuery(query)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
