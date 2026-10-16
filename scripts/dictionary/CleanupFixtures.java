import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

/** Remove only DICT-01 smoke fixtures from the dedicated local test schema. */
class CleanupFixtures {
    public static void main(String[] args) throws Exception {
        String schema = System.getenv("DB_NAME");
        if (!"xinghuiadmintest".equals(schema)) {
            throw new IllegalArgumentException("DB_NAME must be the dedicated xinghuiadmintest schema");
        }
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + schema + "?useSSL=false&serverTimezone=Asia/Shanghai";
        try (Connection connection = DriverManager.getConnection(url, required("DB_USERNAME"), required("DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (PreparedStatement items = connection.prepareStatement(
                    "DELETE FROM sys_dict_item WHERE type_id IN (SELECT type_id FROM sys_dict_type "
                            + "WHERE type_code IN ('dict01_priority','dict01_channel'))");
                 PreparedStatement types = connection.prepareStatement(
                    "DELETE FROM sys_dict_type WHERE type_code IN ('dict01_priority','dict01_channel')")) {
                int itemCount = items.executeUpdate();
                int typeCount = types.executeUpdate();
                connection.commit();
                System.out.println("Removed DICT-01 fixture items=" + itemCount + ", types=" + typeCount);
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
