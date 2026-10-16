package com.xinghuiTec.content;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.utils.SecurityUtils;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Platform content stored in the existing V1 tables. No SQL identifier comes from a request. */
@Service
public class ContentService {
    public enum Module {
        NEWS("sys_news", "news_id", "title",
                "news_id id,title,summary,content,cover_image coverImage,category,tags,status,"
                        + "view_count viewCount,publish_time publishTime");

        final String table;
        final String id;
        final String search;
        final String columns;

        Module(String table, String id, String search, String columns) {
            this.table = table;
            this.id = id;
            this.search = search;
            this.columns = columns;
        }
    }

    public record NewsChange(String title, String summary, String content, String coverImage,
                             String category, String tags, String status) {}

    public record Page(List<Map<String, Object>> records, long total, int pageNum, int pageSize) {}

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> VIEW_FIELDS = List.of(
            "id", "title", "summary", "content", "coverImage", "category", "tags", "status", "viewCount", "publishTime",
            "createTime", "updateTime");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ContentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(Module module, int pageNum, int pageSize, String keyword, String requestedStatus) {
        if (pageNum < 1 || pageNum > 1_000_000 || pageSize < 1 || pageSize > 100) {
            throw invalid("分页参数不合法（每页最多100条）");
        }
        List<Object> parameters = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String filter = text(keyword, 100, true);
            conditions.add(module.search + " LIKE ? ESCAPE '!'");
            parameters.add("%" + filter.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (requestedStatus != null && !requestedStatus.isBlank()) {
            conditions.add("status=?");
            parameters.add(status(module, requestedStatus));
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + module.table + where,
                Long.class, parameters.toArray());
        parameters.add(pageSize);
        parameters.add((long) (pageNum - 1) * pageSize);

        List<Map<String, Object>> rows = jdbc.queryForList(select(module) + where
                + " ORDER BY " + module.id + " DESC LIMIT ? OFFSET ?", parameters.toArray());
        return new Page(rows.stream().map(this::view).toList(), total, pageNum, pageSize);
    }

    public Map<String, Object> get(Module module, String requestedId) {
        List<Map<String, Object>> rows = jdbc.queryForList(select(module) + " WHERE " + module.id + "=?", id(requestedId));
        if (rows.isEmpty()) {
            throw invalid("内容记录不存在");
        }
        return view(rows.get(0));
    }

    @Transactional
    public String createNews(NewsChange request) {
        NewsChange value = validateNews(request);
        String actor = Long.toString(SecurityUtils.getUser().getUserId());
        return insert(Module.NEWS, "INSERT INTO sys_news(title,summary,content,cover_image,category,tags,status,create_by,publish_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,CASE WHEN ?='0' THEN CURRENT_TIMESTAMP ELSE NULL END)",
                value.title(), value.summary(), value.content(), value.coverImage(), value.category(),
                value.tags(), value.status(), actor, value.status());
    }

    @Transactional
    public void updateNews(String requestedId, NewsChange request) {
        long rowId = id(requestedId);
        NewsChange value = validateNews(request);
        int affected = jdbc.update("UPDATE sys_news SET title=?,summary=?,content=?,cover_image=?,category=?,tags=?,status=?,"
                        + "publish_time=CASE WHEN ?='0' THEN COALESCE(publish_time,CURRENT_TIMESTAMP) ELSE NULL END,"
                        + "update_time=CURRENT_TIMESTAMP WHERE news_id=?",
                value.title(), value.summary(), value.content(), value.coverImage(), value.category(),
                value.tags(), value.status(), value.status(), rowId);
        requireChanged(affected);
    }

    @Transactional
    public void remove(Module module, String requestedId) {
        int affected = jdbc.update("DELETE FROM " + module.table + " WHERE " + module.id + "=?", id(requestedId));
        requireChanged(affected);
    }

    private void requireChanged(int affected) {
        if (affected != 1) {
            throw invalid("内容记录不存在或已删除");
        }
    }

    private String insert(Module module, String sql, Object... values) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{module.id});
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        }, key);
        if (key.getKey() == null) {
            throw invalid("新增内容失败");
        }
        return key.getKey().toString();
    }

    private String select(Module module) {
        return "SELECT " + module.columns + ",create_time createTime,update_time updateTime FROM " + module.table;
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : VIEW_FIELDS) {
            if (!row.containsKey(field)) {
                continue;
            }
            Object value = row.get(field);
            if (value != null && field.equals("id")) {
                value = value.toString();
            }
            if (value instanceof Timestamp time) {
                value = time.toLocalDateTime().format(DISPLAY_TIME);
            }
            result.put(field, value);
        }
        return result;
    }

    private NewsChange validateNews(NewsChange request) {
        if (request == null) {
            throw invalid("内容不能为空");
        }
        String state = status(Module.NEWS, request.status());
        String body = large(request.content(), 200_000);
        if (state.equals("0") && body.isBlank()) {
            throw invalid("发布资讯需要正文");
        }
        String tags = request.tags() == null || request.tags().isBlank() ? "[]" : text(request.tags(), 500, false);
        validateTags(tags);
        return new NewsChange(text(request.title(), 255, true), text(request.summary(), 500, false), body,
                url(request.coverImage(), false), text(request.category(), 50, false), tags, state);
    }

    private void validateTags(String tags) {
        try {
            var array = json.readTree(tags);
            if (!array.isArray() || array.size() > 20) {
                throw invalid("标签需为最多20项的JSON字符串数组");
            }
            for (var tag : array) {
                if (!tag.isTextual() || tag.asText().length() > 50) {
                    throw invalid("标签不合法");
                }
            }
        } catch (Exception malformed) {
            throw invalid("标签需为最多20项的JSON字符串数组");
        }
    }

    private String status(Module module, String value) {
        Set<String> allowed = Set.of("0", "1", "2");
        if (value == null || !allowed.contains(value)) {
            throw invalid("状态不合法");
        }
        return value;
    }

    private String text(String value, int max, boolean required) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max || (required && result.isBlank())) {
            throw invalid("字段为空或超过长度限制");
        }
        return result;
    }

    private String large(String value, int maxBytes) {
        String result = value == null ? "" : value;
        if (result.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw invalid("内容超过长度限制");
        }
        return result;
    }

    private String url(String value, boolean required) {
        String result = text(value, 500, required);
        if (result.isEmpty()) {
            return result;
        }
        try {
            if (result.contains("\\") || result.chars().anyMatch(Character::isISOControl)) {
                throw invalid("地址不合法");
            }
            URI uri = URI.create(result);
            boolean relative = result.startsWith("/") && !result.startsWith("//")
                    && uri.getRawAuthority() == null && uri.getScheme() == null;
            boolean web = ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
            if (relative || web) {
                return result;
            }
        } catch (RuntimeException malformed) {
            throw invalid("地址仅允许http/https或合法站内地址");
        }
        throw invalid("地址仅允许http/https或合法站内地址");
    }

    private long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                throw invalid("内容ID不合法");
            }
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw invalid("内容ID不合法");
        }
    }

    private ServiceException invalid(String message) {
        return new ServiceException(message);
    }
}
