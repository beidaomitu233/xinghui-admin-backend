package com.xinghuiTec.notification;

import com.xinghuiTec.exception.ServiceException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Platform-owned global template configuration. Event codes cannot be changed after creation. */
@Service
public class NotificationTemplateService {
    private final JdbcTemplate jdbc;
    private final NotificationTemplateRules rules;

    public NotificationTemplateService(JdbcTemplate jdbc,
            @Value("${notification.allowed-link-hosts:}") String allowedLinkHosts) {
        this.jdbc = jdbc;
        Set<String> hosts = allowedLinkHosts.isBlank() ? Set.of()
                : Set.copyOf(Arrays.asList(allowedLinkHosts.split(",")));
        this.rules = new NotificationTemplateRules(hosts);
    }

    public record Create(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.:-]{0,99}") String eventCode,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 200) String titleTemplate,
            @NotBlank @Size(max = 2000) String bodyTemplate,
            @Size(max = 512) String targetTemplate,
            @NotNull List<String> allowedVariables,
            @NotNull Boolean enabled) {}

    public record Edit(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 200) String titleTemplate,
            @NotBlank @Size(max = 2000) String bodyTemplate,
            @Size(max = 512) String targetTemplate,
            @NotNull List<String> allowedVariables,
            @NotNull Boolean enabled) {}

    public record View(long id, String eventCode, String name, String titleTemplate,
                       String bodyTemplate, String targetTemplate, List<String> allowedVariables,
                       boolean enabled) {}

    public List<View> list() {
        return jdbc.query("""
                SELECT template_id,event_code,template_name,title_template,body_template,
                       target_template,allowed_variables,enabled
                  FROM sys_notification_template ORDER BY event_code
                """, (rs, row) -> new View(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), parseVariables(rs.getString(7)),
                rs.getBoolean(8)));
    }

    @Transactional
    public long create(Create request) {
        validateAdminTemplate(request.titleTemplate(), request.bodyTemplate(), request.targetTemplate(),
                request.allowedVariables());
        KeyHolder holder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO sys_notification_template
                          (event_code,template_name,title_template,body_template,target_template,allowed_variables,enabled)
                        VALUES (?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, request.eventCode());
                statement.setString(2, request.name().trim());
                statement.setString(3, request.titleTemplate());
                statement.setString(4, request.bodyTemplate());
                statement.setString(5, blankToNull(request.targetTemplate()));
                statement.setString(6, String.join(",", request.allowedVariables()));
                statement.setBoolean(7, request.enabled());
                return statement;
            }, holder);
        } catch (DuplicateKeyException duplicate) {
            throw new ServiceException("事件编码已存在");
        }
        return holder.getKey().longValue();
    }

    @Transactional
    public void edit(long id, Edit request) {
        validateAdminTemplate(request.titleTemplate(), request.bodyTemplate(), request.targetTemplate(),
                request.allowedVariables());
        int changed = jdbc.update("""
                UPDATE sys_notification_template
                   SET template_name=?,title_template=?,body_template=?,target_template=?,
                       allowed_variables=?,enabled=? WHERE template_id=?
                """, request.name().trim(), request.titleTemplate(), request.bodyTemplate(),
                blankToNull(request.targetTemplate()), String.join(",", request.allowedVariables()),
                request.enabled(), id);
        if (changed != 1) throw new ServiceException("通知模板不存在");
    }

    public View enabledByCode(String code) {
        List<View> found = jdbc.query("""
                SELECT template_id,event_code,template_name,title_template,body_template,
                       target_template,allowed_variables,enabled
                  FROM sys_notification_template WHERE event_code=?
                """, (rs, row) -> new View(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), parseVariables(rs.getString(7)),
                rs.getBoolean(8)), code);
        if (found.isEmpty()) throw new NotificationContractException("TEMPLATE_NOT_FOUND", "事件模板不存在");
        View template = found.get(0);
        if (!template.enabled()) throw new NotificationContractException("TEMPLATE_DISABLED", "事件模板已停用");
        return template;
    }

    public NotificationTemplateRules rules() {
        return rules;
    }

    private void validateAdminTemplate(String title, String body, String target, List<String> variables) {
        try {
            rules.validate(title, body, target, variables);
        } catch (NotificationContractException invalid) {
            throw new ServiceException(invalid.getMessage());
        }
    }

    private static List<String> parseVariables(String stored) {
        return stored == null || stored.isBlank() ? List.of() : List.of(stored.split(","));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
