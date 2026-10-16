import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

/** Removes only the unique event codes printed by NotificationInboxIsolatedDbTest. */
class CleanupInboxFixtures {
    public static void main(String[] args) throws Exception {
        if (!"xinghuiadmintest".equals(System.getenv("DB_NAME"))) {
            throw new IllegalArgumentException("DB_NAME must be xinghuiadmintest");
        }
        String created = required("NTF02_CREATED_EVENT_CODE");
        String reviewed = required("NTF02_REVIEWED_EVENT_CODE");
        if (!created.startsWith("ntf02.created.") || !reviewed.startsWith("ntf02.reviewed.")) {
            throw new IllegalArgumentException("Event codes must be NTF-02 isolated fixtures");
        }
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String url = "jdbc:mysql://" + host + ":" + port + "/xinghuiadmintest"
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
        try (Connection connection = DriverManager.getConnection(url,
                required("DB_USERNAME"), required("DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                int inbox = delete(connection, "DELETE FROM sys_notification_inbox WHERE event_code IN (?,?)",
                        created, reviewed);
                int requests = delete(connection, "DELETE FROM sys_notification_event_request WHERE event_code IN (?,?)",
                        created, reviewed);
                int templates = delete(connection, "DELETE FROM sys_notification_template WHERE event_code IN (?,?)",
                        created, reviewed);
                connection.commit();
                System.out.println("NTF-02 fixtures removed: inbox=" + inbox + ", requests=" + requests
                        + ", templates=" + templates);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static int delete(Connection connection, String sql, String first, String second) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, first);
            statement.setString(2, second);
            return statement.executeUpdate();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
