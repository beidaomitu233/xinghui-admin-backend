package com.xinghuiTec.service;

import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.utils.RedisCacheUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Platform-owned dictionary configuration and authenticated business lookup. */
@Service
public class DictionaryService {
    private static final int CACHE_SECONDS = 300;
    private final JdbcTemplate jdbc;
    private final RedisCacheUtils redis;

    public DictionaryService(JdbcTemplate jdbc, RedisCacheUtils redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public record TypeCreate(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{0,63}") String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull Boolean enabled,
            @Size(max = 500) String remark) {}

    public record TypeEdit(
            @NotBlank @Size(max = 100) String name,
            @NotNull Boolean enabled,
            @Size(max = 500) String remark) {}

    public record ItemCreate(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String value,
            @NotBlank @Size(max = 128) String label,
            Integer sortOrder,
            @NotNull Boolean enabled,
            @Size(max = 500) String remark) {}

    public record ItemEdit(
            @NotBlank @Size(max = 128) String label,
            Integer sortOrder,
            @NotNull Boolean enabled,
            @Size(max = 500) String remark) {}

    public record TypeView(long id, String code, String name, boolean enabled, String remark, long revision) {}
    public record ItemView(long id, long typeId, String value, String label, int sortOrder,
                           boolean enabled, String remark) implements Serializable {}
    public record Option(String value, String label, int sortOrder) {}
    public record LabelView(String value, String label, boolean known, boolean enabled) {}
    private record Snapshot(boolean enabled, List<ItemView> items) implements Serializable {}

    public List<TypeView> listTypes() {
        return jdbc.query("SELECT type_id,type_code,type_name,enabled,remark,revision FROM sys_dict_type ORDER BY type_code",
                (rs, row) -> new TypeView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getString(5), rs.getLong(6)));
    }

    public List<ItemView> listItems(long typeId) {
        requireType(typeId);
        return queryItems(typeId);
    }

    @Transactional
    public long addType(TypeCreate request) {
        KeyHolder holder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO sys_dict_type(type_code,type_name,enabled,remark) VALUES (?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, request.code().trim());
                statement.setString(2, request.name().trim());
                statement.setBoolean(3, request.enabled());
                statement.setString(4, request.remark());
                return statement;
            }, holder);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("字典类型编码已存在");
        }
        return holder.getKey().longValue();
    }

    @Transactional
    public void editType(long id, TypeEdit request) {
        requireType(id);
        jdbc.update("UPDATE sys_dict_type SET type_name=?,enabled=?,remark=?,revision=revision+1 WHERE type_id=?",
                request.name().trim(), request.enabled(), request.remark(), id);
    }

    @Transactional
    public long addItem(long typeId, ItemCreate request) {
        requireType(typeId);
        KeyHolder holder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO sys_dict_item(type_id,item_value,item_label,sort_order,enabled,remark) VALUES (?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, typeId);
                statement.setString(2, request.value().trim());
                statement.setString(3, request.label().trim());
                statement.setInt(4, request.sortOrder() == null ? 0 : request.sortOrder());
                statement.setBoolean(5, request.enabled());
                statement.setString(6, request.remark());
                return statement;
            }, holder);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("同一字典类型下条目值已存在");
        }
        bumpRevision(typeId);
        return holder.getKey().longValue();
    }

    @Transactional
    public void editItem(long id, ItemEdit request) {
        List<Long> typeIds = jdbc.query("SELECT type_id FROM sys_dict_item WHERE item_id=?",
                (rs, row) -> rs.getLong(1), id);
        if (typeIds.isEmpty()) throw new ServiceException("字典条目不存在");
        jdbc.update("UPDATE sys_dict_item SET item_label=?,sort_order=?,enabled=?,remark=? WHERE item_id=?",
                request.label().trim(), request.sortOrder() == null ? 0 : request.sortOrder(),
                request.enabled(), request.remark(), id);
        bumpRevision(typeIds.get(0));
    }

    /** Only enabled entries in an enabled type may be offered to a new form. */
    public List<Option> options(String code) {
        Snapshot snapshot = snapshot(code);
        if (snapshot == null || !snapshot.enabled()) return List.of();
        return snapshot.items().stream().filter(ItemView::enabled)
                .map(item -> new Option(item.value(), item.label(), item.sortOrder())).toList();
    }

    /** A disabled item retains its label for existing records. Unknown values remain visible verbatim. */
    public LabelView label(String code, String value) {
        Snapshot snapshot = snapshot(code);
        if (snapshot != null) {
            for (ItemView item : snapshot.items()) {
                if (item.value().equals(value)) {
                    return new LabelView(value, item.label(), true, snapshot.enabled() && item.enabled());
                }
            }
        }
        return new LabelView(value, value, false, false);
    }

    private TypeView requireType(long id) {
        List<TypeView> types = jdbc.query(
                "SELECT type_id,type_code,type_name,enabled,remark,revision FROM sys_dict_type WHERE type_id=?",
                (rs, row) -> new TypeView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getString(5), rs.getLong(6)), id);
        if (types.isEmpty()) throw new ServiceException("字典类型不存在");
        return types.get(0);
    }

    private Snapshot snapshot(String code) {
        List<TypeView> types = jdbc.query(
                "SELECT type_id,type_code,type_name,enabled,remark,revision FROM sys_dict_type WHERE type_code=?",
                (rs, row) -> new TypeView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getString(5), rs.getLong(6)), code);
        if (types.isEmpty()) return null;
        TypeView type = types.get(0);
        String key = "dict:snapshot:" + type.code() + ":" + type.revision();
        Snapshot cached = redis.getCacheObject(key);
        if (cached != null) return cached;
        Snapshot fresh = new Snapshot(type.enabled(), queryItems(type.id()));
        redis.setCacheObject(key, fresh, CACHE_SECONDS, TimeUnit.SECONDS);
        return fresh;
    }

    private List<ItemView> queryItems(long typeId) {
        return jdbc.query("SELECT item_id,type_id,item_value,item_label,sort_order,enabled,remark "
                        + "FROM sys_dict_item WHERE type_id=? ORDER BY sort_order,item_id",
                (rs, row) -> new ItemView(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getBoolean(6), rs.getString(7)), typeId);
    }

    private void bumpRevision(long typeId) {
        jdbc.update("UPDATE sys_dict_type SET revision=revision+1 WHERE type_id=?", typeId);
    }
}
