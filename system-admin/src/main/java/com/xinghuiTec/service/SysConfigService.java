package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.entity.SysConfig;
import com.xinghuiTec.domain.dto.ConfigChangeRequest;
import com.xinghuiTec.domain.vo.ConfigView;
import java.util.List;

/**
 * 参数配置表(SysConfig)表服务接口层
 *
 * @since 2025-12-25 19:33:17
 */
public interface SysConfigService extends IService<SysConfig>, PublishedConfigReader {
    List<ConfigView> listManaged();
    ConfigView getManaged(Integer id);
    Integer addManaged(ConfigChangeRequest request);
    void updateManaged(Integer id, ConfigChangeRequest request);
    void removeManaged(Integer id);
}
