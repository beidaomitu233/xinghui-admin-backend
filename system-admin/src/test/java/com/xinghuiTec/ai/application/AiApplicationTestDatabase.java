package com.xinghuiTec.ai.application;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** H2 exercises the B migration except MySQL storage/collation clauses; not MySQL evidence. */
final class AiApplicationTestDatabase {
    private AiApplicationTestDatabase() {}

    static DriverManagerDataSource create() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:ai_application_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        String sql;
        try (var stream = new ClassPathResource("db/migration/V20261016_200__ai_app_runtime_v04.sql")
                .getInputStream()) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        sql = sql.replace(" COLLATE utf8mb4_bin", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V20261016_220__ai_attempt_trace.sql"));
        }
        return source;
    }
}
