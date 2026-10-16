package com.xinghuiTec.ai.template;

import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.lowcode.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LowcodeResourceDeadlineTest {
    private LowcodeResourceReader reader;
    private LowcodeService lowcode;
    private CountDownLatch interrupted;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:deadline_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT,tenant_id VARCHAR(20),status INT,is_deleted INT)");
        jdbc.update("INSERT INTO sys_user VALUES(101,'tenant_a',1,0)");
        var identity = mock(LowcodeIdentity.class); when(identity.tenant()).thenReturn("tenant_a");
        lowcode = mock(LowcodeService.class); interrupted = new CountDownLatch(1);
        when(lowcode.record(1L, 11L)).thenAnswer(call -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException cancelled) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return Map.of("schema", new LowcodeSchema.Schema(List.of()), "data", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), "schemaVersion", 1);
        });
        reader = new LowcodeResourceReader(lowcode, identity, jdbc, new DataSourceTransactionManager(ds));
    }
    @AfterEach void close() { reader.close(); }
    private ResourceDefinition config(int timeout) { return new ResourceDefinition("资源", "测试", "负责人", "lowcode.record", "1", List.of("title"), Set.of("101"), Set.of("201"), timeout); }
    private LowcodeResourceReader.Actor actor() { return new LowcodeResourceReader.Actor("101", "tenant_a", Set.of("lowcode:record:query")); }
    @Test void resourceDeadlineReturnsWhileReadIsBlockedAndInterruptsWorker() throws Exception {
        long start = System.nanoTime();
        var failure = assertThrows(LowcodeResourceReader.ResourceFailure.class, () -> reader.read(config(100), actor(), "201", Map.of("formId", "1", "recordId", "11")));
        assertEquals("TIMEOUT", failure.code());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1000);
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }
    @Test void cancellationInterruptsBlockedReadWithoutWaitingForResourceTimeout() throws Exception {
        var deadline = Instant.now().plusSeconds(10); long cancelAt = System.nanoTime() + 100_000_000;
        var control = new InvocationControl() { public Instant deadline() { return deadline; } public boolean cancelled() { return System.nanoTime() >= cancelAt; } };
        var failure = assertThrows(AiRuntimeException.class, () -> reader.read(config(5000), actor(), "201", Map.of("formId", "1", "recordId", "11"), control));
        assertEquals(ErrorCode.CANCELLED, failure.code()); assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }
    @Test void expiredInvocationDoesNotSubmitAnyRead() {
        var control = new InvocationControl() { public Instant deadline() { return Instant.EPOCH; } public boolean cancelled() { return false; } };
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AiRuntimeException.class, () -> reader.read(config(5000), actor(), "201", Map.of("formId", "1", "recordId", "11"), control)).code());
        verifyNoInteractions(lowcode);
    }
}
