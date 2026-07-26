package com.xinghuiTec.core.mapper;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.reflect.GenericTypeUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * 自定义 Mapper 基类，扩展 MyBatis-Plus BaseMapper
 * <p>
 * 提供实体到 VO 的自动转换方法，减少手动 BeanUtil.copy 样板代码。
 * 用法：public interface SysUserMapper extends BaseMapperPlus<SysUser, SysUserVo> {}
 *
 * @param <T> 实体类型
 * @param <V> VO 类型
 * @author xinghuiTec
 */
@SuppressWarnings("unchecked")
public interface BaseMapperPlus<T, V> extends BaseMapper<T> {

    default Class<V> currentVoClass() {
        return (Class<V>) GenericTypeUtils.resolveTypeArguments(getClass(), BaseMapperPlus.class)[1];
    }

    default Class<T> currentModelClass() {
        return (Class<T>) GenericTypeUtils.resolveTypeArguments(getClass(), BaseMapperPlus.class)[0];
    }

    default List<T> selectList() {
        return selectList(new QueryWrapper<>());
    }

    // ========== VO 查询方法 ==========

    /** 根据 ID 查询 VO */
    default V selectVoById(Serializable id) {
        T obj = selectById(id);
        return ObjectUtil.isNull(obj) ? null : BeanUtil.copyProperties(obj, currentVoClass());
    }

    /** 根据 ID 集合查询 VO 列表 */
    default List<V> selectVoByIds(Collection<? extends Serializable> idList) {
        List<T> list = selectByIds(idList);
        return CollUtil.isEmpty(list) ? CollUtil.newArrayList() : BeanUtil.copyToList(list, currentVoClass());
    }

    /** 根据条件查询单个 VO */
    default V selectVoOne(Wrapper<T> wrapper) {
        T obj = selectOne(wrapper);
        return ObjectUtil.isNull(obj) ? null : BeanUtil.copyProperties(obj, currentVoClass());
    }

    /** 查询所有 VO 列表 */
    default List<V> selectVoList() {
        return selectVoList(new QueryWrapper<>());
    }

    /** 根据条件查询 VO 列表 */
    default List<V> selectVoList(Wrapper<T> wrapper) {
        List<T> list = selectList(wrapper);
        return CollUtil.isEmpty(list) ? CollUtil.newArrayList() : BeanUtil.copyToList(list, currentVoClass());
    }

    /** 分页查询 VO */
    default <P extends IPage<V>> P selectVoPage(IPage<T> page, Wrapper<T> wrapper) {
        List<T> list = selectList(page, wrapper);
        IPage<V> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        if (CollUtil.isNotEmpty(list)) {
            voPage.setRecords(BeanUtil.copyToList(list, currentVoClass()));
        }
        return (P) voPage;
    }

    // ========== 指定 VO 类型的查询方法 ==========

    default <C> C selectVoById(Serializable id, Class<C> voClass) {
        T obj = selectById(id);
        return ObjectUtil.isNull(obj) ? null : BeanUtil.copyProperties(obj, voClass);
    }

    default <C> List<C> selectVoList(Wrapper<T> wrapper, Class<C> voClass) {
        List<T> list = selectList(wrapper);
        return CollUtil.isEmpty(list) ? CollUtil.newArrayList() : BeanUtil.copyToList(list, voClass);
    }

    default <C, P extends IPage<C>> P selectVoPage(IPage<T> page, Wrapper<T> wrapper, Class<C> voClass) {
        List<T> list = selectList(page, wrapper);
        IPage<C> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        if (CollUtil.isNotEmpty(list)) {
            voPage.setRecords(BeanUtil.copyToList(list, voClass));
        }
        return (P) voPage;
    }

}
