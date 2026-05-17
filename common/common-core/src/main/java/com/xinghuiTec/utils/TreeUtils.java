package com.xinghuiTec.utils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 树形结构工具类
 * 提供通用的树形结构构建方法，支持任意类型的树节点
 * 算法复杂度优化：利用 Map 分组，时间复杂度为 O(N)
 *
 * @author beidoa23
 * @since 2026-01-22
 */
public class TreeUtils {

    /**
     * 默认的根节点ID（顶级节点的parentId）
     */
    private static final Long DEFAULT_ROOT_ID = 0L;

    /**
     * 构建树形结构（使用默认根节点ID = 0）
     * 将扁平的列表转换为树形结构
     *
     * @param list         扁平的数据列表
     * @param idGetter     获取节点ID的方法引用，如 SysMenuVO::getMenuId
     * @param parentGetter 获取父节点ID的方法引用，如 SysMenuVO::getParentId
     * @param childSetter  设置子节点列表的方法引用，如 SysMenuVO::setChildren
     * @param orderGetter  获取排序值的方法引用，如 SysMenuVO::getOrderNum（可选，为null则不排序）
     * @param <T>          节点类型
     * @param <K>          节点ID类型（通常是Long）
     * @return 树形结构的列表（只包含顶级节点，子节点嵌套在children中）
     */
    public static <T, K> List<T> buildTree(
            List<T> list,
            Function<T, K> idGetter,
            Function<T, K> parentGetter,
            ChildSetter<T> childSetter,
            Function<T, Integer> orderGetter) {
        // 默认根节点ID为0（Long类型），需调用方确保类型匹配
        @SuppressWarnings("unchecked")
        K rootId = (K) DEFAULT_ROOT_ID;
        return buildTree(list, idGetter, parentGetter, childSetter, orderGetter, rootId);
    }

    /**
     * 构建树形结构（指定根节点ID）
     *
     * @param list         扁平的数据列表
     * @param idGetter     获取节点ID的方法引用
     * @param parentGetter 获取父节点ID的方法引用
     * @param childSetter  设置子节点列表的方法引用
     * @param orderGetter  获取排序值的方法引用（可选）
     * @param rootId       根节点ID（顶级节点的parentId值）
     * @param <T>          节点类型
     * @param <K>          节点ID类型
     * @return 树形结构的列表
     */
    public static <T, K> List<T> buildTree(
            List<T> list,
            Function<T, K> idGetter,
            Function<T, K> parentGetter,
            ChildSetter<T> childSetter,
            Function<T, Integer> orderGetter,
            K rootId) {

        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 按 parentId 分组，构建父子关系映射
        // Key: 父节点ID, Value: 该父节点下的所有直接子节点列表
        Map<K, List<T>> parentMap = list.stream()
                .collect(Collectors.groupingBy(parentGetter));

        // 2. 筛选根节点并递归构建子节点
        return list.stream()
                // 筛选：只放行"根节点"（parentId 等于 rootId）
                .filter(node -> Objects.equals(parentGetter.apply(node), rootId))
                // 组装：递归填充子节点
                .peek(root -> buildChildren(root, parentMap, idGetter, parentGetter, childSetter, orderGetter))
                // 排序：根节点之间按 orderNum 排序
                .sorted(buildComparator(orderGetter))
                // 收集结果
                .collect(Collectors.toList());
    }

    /**
     * 递归填充子节点 (DFS - 深度优先遍历)
     *
     * @param parent       当前处理的父节点
     * @param parentMap    全局的"父子关系"字典
     * @param idGetter     获取节点ID的方法引用
     * @param parentGetter 获取父节点ID的方法引用
     * @param childSetter  设置子节点列表的方法引用
     * @param orderGetter  获取排序值的方法引用
     * @param <T>          节点类型
     * @param <K>          节点ID类型
     */
    private static <T, K> void buildChildren(
            T parent,
            Map<K, List<T>> parentMap,
            Function<T, K> idGetter,
            Function<T, K> parentGetter,
            ChildSetter<T> childSetter,
            Function<T, Integer> orderGetter) {

        // 获取当前节点的ID
        K parentId = idGetter.apply(parent);

        // Optional.ofNullable: 防止 parentMap.get() 返回 null（叶子节点没有子菜单时）
        // orElse: 如果是 null，就给一个空列表
        List<T> children = Optional.ofNullable(parentMap.get(parentId))
                .orElse(Collections.emptyList())
                .stream()
                // 子节点排序
                .sorted(buildComparator(orderGetter))
                // 递归：当前子节点变成新的 parent，去寻找它自己的孩子
                .peek(child -> buildChildren(child, parentMap, idGetter, parentGetter, childSetter, orderGetter))
                .collect(Collectors.toList());

        // 将找到并组装好的子节点列表，挂载到当前父节点下
        childSetter.setChildren(parent, children);
    }

    /**
     * 构建排序比较器
     *
     * @param orderGetter 获取排序值的方法引用
     * @param <T>         节点类型
     * @return 比较器；如果 orderGetter 为 null，返回不改变顺序的比较器
     */
    private static <T> Comparator<T> buildComparator(Function<T, Integer> orderGetter) {
        if (orderGetter == null) {
            // 不排序，保持原有顺序
            return (a, b) -> 0;
        }
        return Comparator.comparingInt(node -> {
            Integer order = orderGetter.apply(node);
            return order != null ? order : Integer.MAX_VALUE;
        });
    }

    /**
     * 设置子节点的函数式接口
     * 用于设置节点的 children 属性
     *
     * @param <T> 节点类型
     */
    @FunctionalInterface
    public interface ChildSetter<T> {
        /**
         * 设置父节点的子节点列表
         *
         * @param parent   父节点
         * @param children 子节点列表
         */
        void setChildren(T parent, List<T> children);
    }
}
