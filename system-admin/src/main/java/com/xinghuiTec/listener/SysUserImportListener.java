package com.xinghuiTec.listener;

import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;

import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.entity.SysRole;
import com.xinghuiTec.domain.excel.SysUserExcel;
import com.xinghuiTec.service.SysRoleService;
import com.xinghuiTec.service.SysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户批量导入数据监听器
 * 监听Excel文件的每一行数据，转换后批量保存到数据库
 *
 * @author beidoa23
 * @since 2025-12-31
 */
@Slf4j
public class SysUserImportListener extends AnalysisEventListener<SysUserExcel> {

    /**
     * 每隔 100 条存储数据库, 然后清理 list，方便内存回收
     */
    private static final int BATCH_COUNT = 100;

    /**
     * 缓存的数据列表
     */
    private List<SysUserAddDTO> cachedDataList = new ArrayList<>(BATCH_COUNT);

    /**
     * 用户服务
     */
    private SysUserService sysUserService;

    /**
     * 角色服务
     */
    private SysRoleService sysRoleService;

    /**
     * 成功导入的数量
     */
    private int successCount = 0;

    /**
     * 失败导入的数量
     */
    private int failCount = 0;

    /**
     * 失败信息列表
     */
    private List<String> failMessages = new ArrayList<>();

    /**
     * 角色缓存 Map<RoleKey, RoleId>
     */
    private final Map<String, Long> roleCache = new HashMap<>();

    public SysUserImportListener(SysUserService sysUserService, SysRoleService sysRoleService) {
        this.sysUserService = sysUserService;
        this.sysRoleService = sysRoleService;
        // 初始化角色缓存
        initRoleCache();
    }

    /**
     * 初始化角色缓存
     */
    private void initRoleCache() {
        List<SysRole> roles = sysRoleService.list();
        if (roles != null && !roles.isEmpty()) {
            for (SysRole role : roles) {
                roleCache.put(role.getRoleKey(), role.getRoleId());
            }
        }
    }

    /**
     * 每解析一行数据，都会调用此方法
     */
    @Override
    public void invoke(SysUserExcel data, AnalysisContext context) {
        log.info("解析到第{}行用户数据: {}", context.readRowHolder().getRowIndex() + 1, data.getUsername());

        try {
            // 将 Excel 数据转换为 DTO
            SysUserAddDTO userDTO = convertToDTO(data);
            cachedDataList.add(userDTO);

            // 达到 BATCH_COUNT 条数据，批量存储一次
            if (cachedDataList.size() >= BATCH_COUNT) {
                saveData();
                cachedDataList.clear();
            }
        } catch (Exception e) {
            failCount++;
            String errorMsg = String.format("第%d行数据导入失败: %s, 原因: %s",
                    context.readRowHolder().getRowIndex() + 1,
                    data.getUsername(),
                    e.getMessage());
            log.error(errorMsg, e);
            failMessages.add(errorMsg);
        }
    }

    /**
     * 所有数据解析完成后会调用此方法
     */
    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // 保存剩余的数据
        saveData();
        log.info("所有数据解析完成！成功导入: {} 条, 失败: {} 条", successCount, failCount);
    }

    /**
     * 批量保存数据
     */
    private void saveData() {
        if (cachedDataList.isEmpty()) {
            return;
        }

        try {
            int count = sysUserService.batchAddUser(cachedDataList);
            successCount += count;
        } catch (Exception e) {
            // 批量失败，全部计入失败
            // 注意：这里无法精确知道具体哪一行失败，除非改写 batchAddUser 返回失败列表
            // 简单起见，暂时全部标记失败
            failCount += cachedDataList.size();
            String errorMsg = String.format("批量导入失败(涉及%d条数据): %s", cachedDataList.size(), e.getMessage());
            log.error(errorMsg, e);
            failMessages.add(errorMsg);
        }
    }

    /**
     * 将 Excel 数据转换为 DTO
     * 根据角色标识（roleKey）查询角色ID
     */
    private SysUserAddDTO convertToDTO(SysUserExcel excel) {
        SysUserAddDTO dto = new SysUserAddDTO();
        dto.setUsername(excel.getUsername());
        dto.setNickname(excel.getNickname());
        dto.setEmail(excel.getEmail());
        dto.setMobile(excel.getMobile());

        // 默认密码为 123456
        dto.setPassword("123456");

        // 状态转换：1-正常，0-停用
        if (StringUtils.hasText(excel.getStatus())) {
            dto.setStatus("正常".equals(excel.getStatus()) ? 1 : 0);
        } else {
            dto.setStatus(1);
        }

        // 角色标识转换为角色ID（格式：admin,common）
        if (StringUtils.hasText(excel.getRoleKeys())) {
            List<Long> roleIds = Arrays.stream(excel.getRoleKeys().split(","))
                    .map(String::trim)
                    .map(roleKey -> {
                        // 从缓存中获取角色ID
                        Long roleId = roleCache.get(roleKey);
                        if (roleId == null) {
                            throw new RuntimeException("角色标识不存在: " + roleKey);
                        }
                        return roleId;
                    })
                    .collect(Collectors.toList());
            dto.setRoleIds(roleIds);
        }

        return dto;
    }

    /**
     * 获取成功导入的数量
     */
    public int getSuccessCount() {
        return successCount;
    }

    /**
     * 获取失败导入的数量
     */
    public int getFailCount() {
        return failCount;
    }

    /**
     * 获取失败信息列表
     */
    public List<String> getFailMessages() {
        return failMessages;
    }
}
