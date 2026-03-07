package com.xinghuiTec.service.impl;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.CircleCaptcha;
import cn.hutool.captcha.ICaptcha;
import cn.hutool.captcha.generator.MathGenerator;
import cn.hutool.captcha.generator.RandomGenerator;
import cn.hutool.core.util.IdUtil;
import com.xinghuiTec.config.CaptchaProperties;
import com.xinghuiTec.constants.CaptchaConstants;
import com.xinghuiTec.domain.vo.CaptchaVO;
import com.xinghuiTec.exception.user.CaptchaExpireException;
import com.xinghuiTec.service.CaptchaService;
import com.xinghuiTec.utils.RedisCacheUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.FastByteArrayOutputStream;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 验证码服务实现类
 * 使用 Hutool 工具库实现验证码的生成、Redis存储和验证功能
 */
@Slf4j
@Service
public class CaptchaServiceImpl implements CaptchaService {

    @Autowired
    private CaptchaProperties captchaProperties;

    @Autowired
    private RedisCacheUtils redisCacheUtils;

    // 注: Redis 键前缀已迁移到 CaptchaConstants.REDIS_KEY_PREFIX

    /**
     * 禁用状态的验证码 VO (静态常量，避免重复创建对象)
     */
    private static final CaptchaVO DISABLED_CAPTCHA = new CaptchaVO("", "", false);

    @Override
    public CaptchaVO generateCaptcha() {
        // 0. 极速返回: 验证码未开启时直接返回静态常量，避免对象创建和日志开销
        if (!captchaProperties.isEnabled()) {
            return DISABLED_CAPTCHA;
        }

        // 1. 生成 UUID
        String uuid = IdUtil.simpleUUID();

        // 2. 创建验证码对象
        ICaptcha captcha = createCaptcha();

        // 3. 生成验证码（会同时生成验证码文本和图片）
        captcha.createCode();

        // 4. 获取验证码答案
        String captchaAnswer = captcha.getCode();

        // 5. 将验证码图片转为 Base64
        String base64Image = captchaToBase64(captcha);

        // 6. 将验证码答案存储到 Redis,设置过期时间
        String redisKey = CaptchaConstants.REDIS_KEY_PREFIX + uuid;
        redisCacheUtils.setCacheObject(
                redisKey,
                captchaAnswer,
                captchaProperties.getExpireTime(),
                TimeUnit.MINUTES);

        log.info("生成验证码 - UUID: {}, 类型: {}, 答案: {}", uuid, captchaProperties.getType(), captchaAnswer);

        // 7. 返回 CaptchaVO
        return new CaptchaVO(uuid, base64Image, true);
    }

    @Override
    public boolean validateCaptcha(String uuid, String code) {
        // 1. 从 Redis 中获取验证码
        String redisKey = CaptchaConstants.REDIS_KEY_PREFIX + uuid;
        String cachedCode = redisCacheUtils.getCacheObject(redisKey);

        // 2. 判断验证码是否存在(可能已过期)
        if (cachedCode == null) {
            log.warn("验证码验证失败 - UUID: {}, 原因: 验证码不存在或已过期", uuid);
            throw new CaptchaExpireException();
        }

        // 3. 删除验证码(一次性使用)
        redisCacheUtils.deleteObject(redisKey);

        // 4. 比较验证码(忽略大小写)
        boolean isValid = cachedCode.equalsIgnoreCase(code);

        if (isValid) {
            log.info("验证码验证成功 - UUID: {}", uuid);
        } else {
            log.warn("验证码验证失败 - UUID: {}, 期望: {}, 实际: {}", uuid, cachedCode, code);
        }

        return isValid;
    }

    /**
     * 根据配置创建验证码对象
     * 
     * @return ICaptcha 验证码对象
     */
    private ICaptcha createCaptcha() {
        int width = captchaProperties.getWidth();
        int height = captchaProperties.getHeight();
        int codeLength = captchaProperties.getCodeLength();

        // 使用圆圈干扰验证码（效果更好）
        CircleCaptcha captcha = CaptchaUtil.createCircleCaptcha(width, height, codeLength, 20);

        // 根据类型设置验证码生成器
        if (CaptchaConstants.TYPE_NUMERIC.equals(captchaProperties.getType())) {
            // 数字类型: 纯数字验证码
            RandomGenerator randomGenerator = new RandomGenerator("0123456789", codeLength);
            captcha.setGenerator(randomGenerator);
            log.debug("使用数字验证码生成器，长度: {}", codeLength);
        } else {
            // 算术类型: 使用算术运算验证码
            captcha.setGenerator(new MathGenerator());
            log.debug("使用算术运算验证码生成器");
        }

        return captcha;
    }

    /**
     * 将验证码对象转为 Base64 字符串
     * 
     * @param captcha 验证码对象
     * @return Base64 编码的图片字符串
     */
    private String captchaToBase64(ICaptcha captcha) {
        FastByteArrayOutputStream os = new FastByteArrayOutputStream();
        captcha.write(os);
        return Base64.getEncoder().encodeToString(os.toByteArray());
    }
}
