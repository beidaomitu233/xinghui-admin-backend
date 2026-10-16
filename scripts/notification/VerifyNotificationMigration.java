import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Read-only NTF-01 schema check, pinned to the dedicated isolated database. */
class VerifyNotificationMigration {
    public static void main(String[] args) throws Exception {
        if (!"xinghuiadmintest".equals(System.getenv("DB_NAME"))) {
            throw new IllegalArgumentException("DB_NAME must be xinghuiadmintest");
        }
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String url = "jdbc:mysql://" + host + ":" + port + "/xinghuiadmintest"
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
        try (Connection connection = DriverManager.getConnection(url,
                required("DB_USERNAME"), required("DB_PASSWORD"));
                Statement statement = connection.createStatement()) {
            check(statement, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='20260926.50' AND success=1", 1);
            check(statement, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='20260926.60' AND success=1", 1);
            check(statement, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='20260926.70' AND success=1", 1);
            check(statement, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='20260926.80' AND success=1", 1);
            check(statement, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='xinghuiadmintest' "
                    + "AND table_name IN ('sys_notice','sys_notification_template','sys_notification_event_request',"
                    + "'sys_notification_inbox')", 4);
            check(statement, "SELECT COUNT(*) FROM sys_menu WHERE menu_id IN (105,1030,1031)", 3);
            System.out.println("NTF migrations verified in xinghuiadmintest: V60, V70, V80; notification and announcement tables retained");
        }
    }

    private static void check(Statement statement, String sql, int expected) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            int actual = result.getInt(1);
            if (actual != expected) throw new IllegalStateException("Expected " + expected + " but found " + actual + " for " + sql);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
