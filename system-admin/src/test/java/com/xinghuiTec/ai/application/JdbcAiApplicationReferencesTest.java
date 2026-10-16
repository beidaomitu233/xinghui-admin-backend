package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiTypes.ModelVersionRef;
import com.xinghuiTec.ai.contract.AiTypes.ResourceVersionRef;
import com.xinghuiTec.ai.contract.AiTypes.TemplateVersionRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class JdbcAiApplicationReferencesTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private JdbcAiApplicationReferences instanceA;
    private JdbcAiApplicationReferences instanceB;

    @BeforeEach
    void setup() throws Exception {
        var source = AiApplicationTestDatabase.create();
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        instanceA = new JdbcAiApplicationReferences(jdbc, manager);
        instanceB = new JdbcAiApplicationReferences(jdbc, manager);
        jdbc.update("INSERT INTO sys_ai_application(app_id,name,purpose,owner_id,entry_key,draft_json)"
                + " VALUES(1,'summary','record summary',101,'lowcode.record.summary','{}')");
        jdbc.update("INSERT INTO sys_ai_application_version(app_version_id,app_id,version_number,draft_revision,"
                + "snapshot_json,tested_request_id,created_by) VALUES(11,1,1,1,'{}',1,101),(12,1,2,2,'{}',2,101)");
        jdbc.update("INSERT INTO sys_ai_application_reference VALUES"
                + "(11,'MODEL','model1','config-v1'),(11,'TEMPLATE','template1','template-v1'),"
                + "(11,'RESOURCE','resource1','resource-v1'),(12,'MODEL','model1','config-v2')");
        jdbc.update("UPDATE sys_ai_application SET enabled=TRUE,published_version_id=11 WHERE app_id=1");
    }

    @Test
    void reportsOnlyEnabledOnlineExactVersionReferences() {
        assertEquals(1, instanceA.modelReferences(new ModelVersionRef("model1", "config-v1")).size());
        assertTrue(instanceA.modelReferences(new ModelVersionRef("model1", "config-v2")).isEmpty());
        assertEquals("11", instanceA.templateReferences(new TemplateVersionRef("template1", "template-v1"))
                .get(0).appVersionId());
        assertEquals("1", instanceA.resourceReferences(new ResourceVersionRef("resource1", "resource-v1"))
                .get(0).appId());
        jdbc.update("UPDATE sys_ai_application SET enabled=FALSE WHERE app_id=1");
        assertTrue(instanceA.modelReferences(new ModelVersionRef("model1", "config-v1")).isEmpty());
    }

    @Test
    void guardPersistsThroughOuterCommitAndSecondInstanceSeesNewPublication() throws Exception {
        var operationReturned = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var secondEnteredGuard = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var publication = pool.submit(() -> transaction.execute(status -> {
                instanceA.withReferenceGuard(() -> {
                    jdbc.update("UPDATE sys_ai_application SET published_version_id=12 WHERE app_id=1");
                    return null;
                });
                operationReturned.countDown();
                await(allowCommit);
                return true;
            }));
            assertTrue(operationReturned.await(5, TimeUnit.SECONDS));
            var dependencyChange = pool.submit(() -> instanceB.withReferenceGuard(() -> {
                secondEnteredGuard.countDown();
                return instanceB.modelReferences(new ModelVersionRef("model1", "config-v2"));
            }));
            assertFalse(secondEnteredGuard.await(150, TimeUnit.MILLISECONDS));
            allowCommit.countDown();
            assertTrue(publication.get(5, TimeUnit.SECONDS));
            assertEquals("12", dependencyChange.get(5, TimeUnit.SECONDS).get(0).appVersionId());
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void mutationRollsBackWithGuardAndMissingLockFailsClosed() {
        assertThrows(IllegalStateException.class, () -> instanceA.withReferenceGuard(() -> {
            jdbc.update("UPDATE sys_ai_application SET enabled=FALSE WHERE app_id=1");
            throw new IllegalStateException("abort");
        }));
        assertFalse(instanceB.modelReferences(new ModelVersionRef("model1", "config-v1")).isEmpty());
        jdbc.update("DELETE FROM sys_ai_reference_guard");
        var executed = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> instanceA.withReferenceGuard(() -> {
            executed.set(true);
            return null;
        }));
        assertFalse(executed.get());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test coordination timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted");
        }
    }
}
