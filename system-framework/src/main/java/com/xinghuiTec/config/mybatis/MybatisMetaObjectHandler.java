package com.xinghuiTec.config.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MybatisMetaObjectHandler 是一个 MyBatis-Plus 的元对象处理器，
 * 用于在插入和更新数据时自动填充指定的字段。
 */
@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {

    /**
     * 在插入数据时自动填充创建时间字段。
     *
     * @param metaObject 元对象，包含待插入的数据
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", Date.class, new Date());
    }

    /**
     * 在更新数据时自动填充更新时间字段。
     *
     * @param metaObject 元对象，包含待更新的数据
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", Date.class, new Date());
    }
}