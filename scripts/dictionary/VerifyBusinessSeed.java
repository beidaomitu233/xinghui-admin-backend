import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Read-only check against the authorized isolated DICT test database. */
class VerifyBusinessSeed {
    public static void main(String[] args) throws Exception {
        String database = System.getenv("DB_NAME");
        if (!"xinghuiadmintest".equals(database)) {
            throw new IllegalArgumentException("DB_NAME must be xinghuiadmintest");
        }
        String url = "jdbc:mysql://127.0.0.1:3306/" + database
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
        try (Connection connection = DriverManager.getConnection(
                url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
            verify(connection, "notice_type", "1", "通知卡片");
            verify(connection, "notice_type", "2", "强弹窗");
            verify(connection, "job_status", "0", "启用配置");
            verify(connection, "job_status", "1", "暂停");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='20260926.40' AND success=1");
                 ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getInt(1) != 1) throw new IllegalStateException("Flyway V40 not successful");
            }
        }
        System.out.println("V40 successful; notice_type and job_status each have two expected enabled values");
    }

    private static void verify(Connection connection, String code, String value, String label) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.item_label, i.enabled, t.enabled, HEX(i.item_label)
                  FROM sys_dict_type t JOIN sys_dict_item i ON i.type_id=t.type_id
                 WHERE t.type_code=? AND i.item_value=?
                """)) {
            statement.setString(1, code);
            statement.setString(2, value);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Missing entry: " + code + "/" + value);
                String actualLabel = rows.getString(1);
                boolean itemEnabled = rows.getBoolean(2);
                boolean typeEnabled = rows.getBoolean(3);
                String hex = rows.getString(4);
                boolean duplicate = rows.next();
                if (!label.equals(actualLabel) || !itemEnabled || !typeEnabled || duplicate) {
                    throw new IllegalStateException("Unexpected dictionary entry: " + code + "/" + value
                            + " label=" + actualLabel + " expected=" + label
                            + " itemEnabled=" + itemEnabled + " typeEnabled=" + typeEnabled
                            + " duplicate=" + duplicate + " hex=" + hex);
                }
            }
        }
    }
}
