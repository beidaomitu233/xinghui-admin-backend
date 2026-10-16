package com.xinghuiTec.ai.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.OptionalInt;

/** A database row lock shares the saved model order across all application instances. */
@Repository
public class AiSharedModelCursor {
    private final JdbcTemplate jdbc;

    public AiSharedModelCursor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Called once for a new request's first model choice, never once per Key or retry.
     * Eligibility is supplied by A's current model availability, in immutable snapshot order.
     * Publication must initialize the cursor row in the same transaction as the version.
     */
    @Transactional
    public OptionalInt next(long appVersionId, List<Boolean> eligibleInSavedOrder) {
        if (appVersionId <= 0 || eligibleInSavedOrder == null || eligibleInSavedOrder.isEmpty()
                || eligibleInSavedOrder.size() > 32 || eligibleInSavedOrder.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid model order");
        }
        List<Long> rows = jdbc.query("SELECT next_index FROM sys_ai_application_cursor"
                        + " WHERE app_version_id=? FOR UPDATE", (rs, index) -> rs.getLong(1), appVersionId);
        if (rows.size() != 1) throw new IllegalStateException("Application cursor unavailable");
        int count = eligibleInSavedOrder.size();
        int start = Math.floorMod(rows.get(0), count);
        for (int offset = 0; offset < count; offset++) {
            int selected = (start + offset) % count;
            if (Boolean.TRUE.equals(eligibleInSavedOrder.get(selected))) {
                jdbc.update("UPDATE sys_ai_application_cursor SET next_index=? WHERE app_version_id=?",
                        (selected + 1) % count, appVersionId);
                return OptionalInt.of(selected);
            }
        }
        return OptionalInt.empty();
    }
}
