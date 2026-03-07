package com.xinghuiTec.service;

import com.xinghuiTec.domain.vo.CaptchaVO;

/**
 * 验证码服务接口
 */
public interface CaptchaService {

    /**
     * 生成验证码
     * 
     * @return 验证码 VO 对象,包含 UUID 和 Base64 图片
     */
    CaptchaVO generateCaptcha();

    /**
     * 校验验证码
     * 
     * @param uuid 验证码唯一标识
     * @param code 用户输入的验证码
     * @return true: 验证成功, false: 验证失败
     */
    boolean validateCaptcha(String uuid, String code);
}
