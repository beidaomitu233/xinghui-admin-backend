package com.xinghuiTec.notification;

import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Owner-only inbox reads and persistent read state. Neither owner nor tenant comes from a request body. */
@Service
public class NotificationInboxService {
    private final JdbcTemplate jdbc;
    private final boolean tenantEnabled;

    public NotificationInboxService(JdbcTemplate jdbc,
            @Value("${tenant.enable:false}") boolean tenantEnabled) {
        this.jdbc = jdbc;
        this.tenantEnabled = tenantEnabled;
    }

    public record Item(long id, String eventCode, String eventKey, String title, String body,
                       String target, LocalDateTime createTime, LocalDateTime readTime) {}
    public record Page(List<Item> items, long total, int pageNum, int pageSize) {}

    public Page page(int pageNum, int pageSize, boolean unreadOnly) {
        Owner owner = owner();
        if (pageNum < 1 || pageNum > 10_000 || pageSize < 1 || pageSize > 100) {
            throw new ServiceException("页码或每页数量无效（每页最多 100 条）");
        }
        String filter = unreadOnly ? " AND read_time IS NULL" : "";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM sys_notification_inbox "
                        + "WHERE tenant_id=? AND recipient_user_id=?" + filter,
                Long.class, owner.tenantId(), owner.userId());
        List<Item> items = jdbc.query("""
                SELECT notification_id,event_code,event_key,title,body,target,create_time,read_time
                  FROM sys_notification_inbox WHERE tenant_id=? AND recipient_user_id=?
                """ + filter + " ORDER BY create_time DESC,notification_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> item(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7), rs.getTimestamp(8)),
                owner.tenantId(), owner.userId(), pageSize, (pageNum - 1) * pageSize);
        return new Page(items, total, pageNum, pageSize);
    }

    public Item detail(long id) {
        Owner owner = owner();
        if (id <= 0) throw new ServiceException("通知 ID 无效");
        List<Item> items = jdbc.query("""
                SELECT notification_id,event_code,event_key,title,body,target,create_time,read_time
                  FROM sys_notification_inbox
                 WHERE notification_id=? AND tenant_id=? AND recipient_user_id=?
                """, (rs, row) -> item(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getTimestamp(7), rs.getTimestamp(8)), id, owner.tenantId(), owner.userId());
        if (items.isEmpty()) throw new ServiceException("通知不存在");
        return items.get(0);
    }

    public long unreadCount() {
        Owner owner = owner();
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_notification_inbox
                 WHERE tenant_id=? AND recipient_user_id=? AND read_time IS NULL
                """, Long.class, owner.tenantId(), owner.userId());
    }

    public void markRead(long id) {
        Owner owner = owner();
        if (id <= 0) throw new ServiceException("通知 ID 无效");
        Long owned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_notification_inbox
                 WHERE notification_id=? AND tenant_id=? AND recipient_user_id=?
                """, Long.class, id, owner.tenantId(), owner.userId());
        if (owned == null || owned != 1) throw new ServiceException("通知不存在");
        jdbc.update("""
                UPDATE sys_notification_inbox SET read_time=CURRENT_TIMESTAMP(3)
                 WHERE notification_id=? AND tenant_id=? AND recipient_user_id=? AND read_time IS NULL
                """, id, owner.tenantId(), owner.userId());
    }

    @Transactional
    public int markReadBatch(List<Long> ids) {
        Owner owner = owner();
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ServiceException("通知 ID 列表无效（最多 100 条）");
        }
        Set<Long> unique = new LinkedHashSet<>(ids);
        String markers = unique.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[unique.size() + 2];
        args[0] = owner.tenantId();
        args[1] = owner.userId();
        int index = 2;
        for (Long id : unique) args[index++] = id;
        Long owned = jdbc.queryForObject("SELECT COUNT(*) FROM sys_notification_inbox "
                + "WHERE tenant_id=? AND recipient_user_id=? AND notification_id IN (" + markers + ")",
                Long.class, args);
        if (owned == null || owned != unique.size()) throw new ServiceException("通知不存在");
        jdbc.update("UPDATE sys_notification_inbox SET read_time=CURRENT_TIMESTAMP(3) "
                + "WHERE tenant_id=? AND recipient_user_id=? AND read_time IS NULL "
                + "AND notification_id IN (" + markers + ")", args);
        return unique.size();
    }

    private Owner owner() {
        LoginUser login = SecurityUtils.getLoginUser();
        if (login == null || login.getUser() == null || login.getUser().getUserId() == null) {
            throw new AccessDeniedException("未登录");
        }
        long userId = login.getUser().getUserId();
        List<CurrentUser> users = jdbc.query("SELECT tenant_id,status,is_deleted FROM sys_user WHERE user_id=?",
                (rs, row) -> new CurrentUser(rs.getString(1), rs.getInt(2), rs.getInt(3)), userId);
        if (users.isEmpty() || users.get(0).status() != 1 || users.get(0).deleted() != 0) {
            throw new AccessDeniedException("账号不可用");
        }
        String tenantId = tenantEnabled ? login.getTenantId() : TenantConstants.DEFAULT_TENANT_ID;
        if (tenantId == null || !tenantId.equals(login.getUser().getTenantId())
                || !tenantId.equals(users.get(0).tenantId())) {
            throw new AccessDeniedException("租户身份无效");
        }
        return new Owner(tenantId, userId);
    }

    private static Item item(long id, String code, String key, String title, String body,
                             String target, Timestamp created, Timestamp read) {
        return new Item(id, code, key, title, body, target,
                created.toLocalDateTime(), read == null ? null : read.toLocalDateTime());
    }

    private record Owner(String tenantId, long userId) {}
    private record CurrentUser(String tenantId, int status, int deleted) {}
}
