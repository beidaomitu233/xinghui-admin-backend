package com.xinghuiTec.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 验证码返回对象
 * 用于向前端返回验证码图片和相关信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CaptchaVO {

    /**
     * 验证码的唯一标识 (UUID)
     * 前端需要在登录时一起提交此 UUID
     */
    private String uuid;

    /**
     * Base64 编码的验证码图片字符串
     * 前端可以直接使用: <img src="data:image/png;base64,{img}" />
     */
    private String img;

    /**
     * 验证码是否启用
     * true: 启用, false: 未启用
     */
    private Boolean enabled;
}
