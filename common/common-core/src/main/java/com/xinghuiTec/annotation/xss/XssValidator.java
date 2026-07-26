package com.xinghuiTec.annotation.xss;

import cn.hutool.core.util.ReUtil;
import cn.hutool.http.HtmlUtil;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * XSS 校验器
 * 检查字符串是否包含 HTML 标签（如 &lt;script&gt;、&lt;img onerror=...&gt; 等）
 * 使用 Hutool 内置的 HTML 正则 RE_HTML_MARK 匹配
 *
 * @author xinghuiTec
 */
public class XssValidator implements ConstraintValidator<Xss, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;  // 空值由 @NotNull/@NotBlank 处理
        }
        // 如果包含 HTML 标签，则校验不通过
        return !ReUtil.contains(HtmlUtil.RE_HTML_MARK, value);
    }
}
