package com.xinghuiTec.config.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.xinghuiTec.domain.entity.BaseEntity;
import com.xinghuiTec.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MyBatis-Plus 元对象自动填充处理器
 * 自动填充：创建人、更新人、创建时间、更新时间
 *
 * @author xinghuiTec
 */
@Slf4j
@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {

    /** 未登录时的默认用户ID */
    private static final String DEFAULT_USER_ID = "-1";

    @Override
    public void insertFill(MetaObject metaObject) {
        try {
            Date now = new Date();
            if (metaObject.getOriginalObject() instanceof BaseEntity entity) {
                // 已从 entity 中获取到对象，直接设置
                if (entity.getCreateTime() == null) {
                    entity.setCreateTime(now);
                }
                if (entity.getUpdateTime() == null) {
                    entity.setUpdateTime(now);
                }
                if (entity.getCreateBy() == null) {
                    entity.setCreateBy(getLoginUserId());
                }
                if (entity.getUpdateBy() == null) {
                    entity.setUpdateBy(getLoginUserId());
                }
            } else {
                // 非 BaseEntity 子类，使用严格填充
                this.strictInsertFill(metaObject, "createTime", Date.class, now);
                this.strictInsertFill(metaObject, "updateTime", Date.class, now);
            }
        } catch (Exception e) {
            log.warn("insertFill 自动填充失败: {}", e.getMessage());
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        try {
            Date now = new Date();
            if (metaObject.getOriginalObject() instanceof BaseEntity entity) {
                entity.setUpdateTime(now);
                entity.setUpdateBy(getLoginUserId());
            } else {
                this.strictUpdateFill(metaObject, "updateTime", Date.class, now);
            }
        } catch (Exception e) {
            log.warn("updateFill 自动填充失败: {}", e.getMessage());
        }
    }

    /**
     * 获取当前登录用户ID，未登录返回默认值
     */
    private String getLoginUserId() {
        try {
            String userId = SecurityUtils.getUserId();
            return userId != null ? userId : DEFAULT_USER_ID;
        } catch (Exception e) {
            return DEFAULT_USER_ID;
        }
    }

}
