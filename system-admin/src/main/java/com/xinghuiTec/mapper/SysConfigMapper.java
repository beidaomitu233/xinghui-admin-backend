package com.xinghuiTec.mapper;

import com.xinghuiTec.domain.entity.SysConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
/**
 * 参数配置表(SysConfig)表数据库访问层
 *
 * @since 2025-12-25 19:33:17
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig>{
    // config_key is globally unique, so soft deletion would prevent later recreation.
    @Delete("DELETE FROM sys_config WHERE config_id = #{id} AND tenant_id = '000000' " +
            "AND config_key = 'sys.index.footerText' AND config_type = 'N'")
    int deleteOptionalFooter(@Param("id") Integer id);
}
