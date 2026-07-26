package com.xinghuiTec.annotation.xss;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * XSS 防护校验注解
 * 用于校验字符串是否包含 HTML/脚本标签
 *
 * <pre>
 * // 在 DTO 字段上使用
 * public class NoticeAddDTO {
 *     &#64;Xss
 *     private String noticeTitle;
 * }
 * </pre>
 *
 * @author xinghuiTec
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.PARAMETER})
@Constraint(validatedBy = {XssValidator.class})
public @interface Xss {

    String message() default "不允许包含HTML标签或脚本";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
