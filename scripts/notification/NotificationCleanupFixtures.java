import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

/** Deletes only this task's two neutral templates from the dedicated test schema. */
class NotificationCleanupFixtures {
    private static final String FIRST = "ntf01.sample_a.created";
    private static final String SECOND = "ntf01.sample_b.reviewed";

    public static void main(String[] args) throws Exception {
        if (!"xinghuiadmintest".equals(System.getenv("DB_NAME"))) {
            throw new IllegalArgumentException("DB_NAME must be xinghuiadmintest");
        }
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String url = "jdbc:mysql://" + host + ":" + port + "/xinghuiadmintest"
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
        try (Connection connection = DriverManager.getConnection(url,
                required("DB_USERNAME"), required("DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (PreparedStatement requests = connection.prepareStatement(
                    "DELETE FROM sys_notification_event_request WHERE event_code IN (?,?)");
                    PreparedStatement templates = connection.prepareStatement(
                            "DELETE FROM sys_notification_template WHERE event_code IN (?,?)")) {
                requests.setString(1, FIRST);
                requests.setString(2, SECOND);
                templates.setString(1, FIRST);
                templates.setString(2, SECOND);
                int requestCount = requests.executeUpdate();
                int templateCount = templates.executeUpdate();
                connection.commit();
                System.out.println("NTF-01 fixtures removed: requests=" + requestCount
                        + ", templates=" + templateCount);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
