package com.xinghuiTec.core;

import com.xinghuiTec.annotation.xss.XssValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("校验器测试")
public class ValidatorTest {

    private final XssValidator xssValidator = new XssValidator();

    @Test
    @DisplayName("XSS - 正常文本通过")
    void testXssNormalText() {
        assertTrue(xssValidator.isValid("普通文本", null));
        assertTrue(xssValidator.isValid("Hello World", null));
        assertTrue(xssValidator.isValid("包含中文和数字123", null));
        System.out.println("✓ 正常文本通过 XSS 校验");
    }

    @Test
    @DisplayName("XSS - 空值通过")
    void testXssEmpty() {
        assertTrue(xssValidator.isValid(null, null));
        assertTrue(xssValidator.isValid("", null));
        System.out.println("✓ 空值通过 XSS 校验");
    }

    @Test
    @DisplayName("XSS - script 标签被拦截")
    void testXssScriptTag() {
        assertFalse(xssValidator.isValid("<script>alert('xss')</script>", null));
        System.out.println("✓ <script> 标签被拦截");
    }

    @Test
    @DisplayName("XSS - HTML 标签被拦截")
    void testXssHtmlTag() {
        assertFalse(xssValidator.isValid("<div>hello</div>", null));
        assertFalse(xssValidator.isValid("<img src=x onerror=alert(1)>", null));
        System.out.println("✓ HTML 标签被拦截");
    }
}
