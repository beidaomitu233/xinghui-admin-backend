package com.xinghuiTec.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 验证码配置属性类
 * 从 application.yml 中读取 captcha 前缀的配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "captcha")
public class CaptchaProperties {

    /**
     * 是否启用验证码功能
     * 默认值: false
     */
    private Boolean enabled = false;

    /**
     * 验证码类型
     * numeric: 4位数字验证码
     * arithmetic: 算术运算验证码 (两位数+两位数)
     * 默认值: arithmetic
     */
    private String type = "arithmetic";

    /**
     * 验证码过期时间 (分钟)
     * 默认值: 5分钟
     */
    private Integer expireTime = 5;

    /**
     * 验证码图片宽度
     * 默认值: 120像素
     */
    private Integer width = 120;

    /**
     * 验证码图片高度
     * 默认值: 40像素
     */
    private Integer height = 40;

    /**
     * 数字验证码长度
     * 默认值: 4位
     */
    private Integer codeLength = 4;

    /**
     * 快速判断验证码是否启用（性能优化）
     * 避免每次都调用 getEnabled() 进行 Boolean 拆箱
     * 
     * @return true: 启用, false: 禁用
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
