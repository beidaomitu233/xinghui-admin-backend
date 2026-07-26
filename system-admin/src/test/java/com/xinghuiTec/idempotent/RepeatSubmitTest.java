package com.xinghuiTec.idempotent;

import com.xinghuiTec.idempotent.annotation.RepeatSubmit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("防重提交模块测试")
public class RepeatSubmitTest {

    @Test
    @DisplayName("@RepeatSubmit 注解属性默认值")
    void testAnnotationDefaults() throws NoSuchMethodException {
        RepeatSubmit annotation = TestService.class
            .getMethod("testDefault")
            .getAnnotation(RepeatSubmit.class);

        assertNotNull(annotation);
        assertEquals(5000, annotation.interval());
        assertEquals(TimeUnit.MILLISECONDS, annotation.timeUnit());
        assertEquals("不允许重复提交，请稍候再试", annotation.message());
        System.out.println("✓ @RepeatSubmit 默认值验证通过");
    }

    @Test
    @DisplayName("@RepeatSubmit 自定义间隔")
    void testCustomInterval() throws NoSuchMethodException {
        RepeatSubmit annotation = TestService.class
            .getMethod("testCustom")
            .getAnnotation(RepeatSubmit.class);

        assertEquals(3000, annotation.interval());
        System.out.println("✓ 自定义间隔验证通过");
    }

    static class TestService {
        @RepeatSubmit
        public void testDefault() {}

        @RepeatSubmit(interval = 3000)
        public void testCustom() {}
    }
}
