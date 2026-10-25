package com.xinghuiTec.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.runtime.AgentWorkbench;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Duration;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.*;

class JdbcAgentRepositoryTest {
    @TempDir Path workspace;
    JdbcTemplate jdbc;
    JdbcAgentRepository repository;
    ObjectMapper json = new ObjectMapper();
    AgentActor actor = new AgentActor("brand-a", "42");
    AtomicBoolean historyAllowed = new AtomicBoolean(true);
    AgentPayloadCodec codec = new AgentPayloadCodec() {
        public String seal(String purpose, String plaintext) { return Base64.getEncoder().encodeToString((purpose + "\n" + plaintext).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        public String open(String purpose, String ciphertext) {
            String text = new String(Base64.getDecoder().decode(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
            if (!text.startsWith(purpose + "\n")) throw new IllegalStateException("Purpose mismatch"); return text.substring(purpose.length() + 1);
        }
    };
    @BeforeEach void database() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261024_400__agent_workbench.sql"),new ClassPathResource("db/migration/V20261025_430__agent_images.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        repository = new JdbcAgentRepository(jdbc, new DataSourceTransactionManager(ds), codec, (owner, resources) -> {
            if (!historyAllowed.get() && !resources.isEmpty()) throw new AgentProblem("FORBIDDEN", "资源授权已变更");
        }, json);
        jdbc.execute("CREATE TABLE qa_business(id INT PRIMARY KEY, name VARCHAR(100))");
    }
    private JdbcAgentRepository.Admission submit(String session, String id, String input) {
        return repository.admit(actor, session, id, input, "scene.qa", Duration.ofMinutes(1));
    }
    private AgentCall call(JdbcAgentRepository.Run run) { return new AgentCall(actor, run.sessionId(), run.id(), "model-1", run.deadline(), () -> !repository.running(actor, run.id())); }
    @Test void imageHistoryKeepsOwnedReferencesAndSubmissionRetriesKeepTheSameAttachments(){
        var session=repository.create(actor,"图片任务");
        var images=new JdbcAgentImages(jdbc,repository,codec,json);
        byte[] bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aYXcAAAAASUVORK5CYII=");
        var image=images.save(actor,session.id(),"商品.png","image/png",bytes);
        assertThat(images.read(actor,session.id(),image.id()).bytes()).isEqualTo(bytes);
        assertThatThrownBy(()->images.read(new AgentActor("brand-b","42"),session.id(),image.id())).isInstanceOf(AgentProblem.class);
        var first=repository.admit(actor,session.id(),"client_request_image1","照图新增商品","scene.qa",Duration.ofMinutes(1),List.of(image));
        var replay=repository.admit(actor,session.id(),"client_request_image1","照图新增商品","scene.qa",Duration.ofMinutes(1),List.of(image));
        assertThat(replay.run().id()).isEqualTo(first.run().id());
        assertThat(repository.claim(actor,first.run().id()).imageIds()).containsExactly(image.id());
        assertThat(repository.events(actor,session.id(),0).get(0).data().get("images").toString()).contains(image.id()).doesNotContain(Base64.getEncoder().encodeToString(bytes));
        assertThatThrownBy(()->repository.admit(actor,session.id(),"client_request_image1","照图新增商品","scene.qa",Duration.ofMinutes(1),List.of())).isInstanceOf(AgentProblem.class);
    }

    @Test void concurrentDuplicateSubmissionCreatesOnlyOneRunAndUserMessage() throws Exception {
        var session = repository.create(actor, "QA 任务");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var latch = new CountDownLatch(1);
            Callable<JdbcAgentRepository.Admission> request = () -> { latch.await(); return submit(session.id(), "client_request_0001", "QA新增"); };
            var one = executor.submit(request); var two = executor.submit(request); latch.countDown();
            var a = one.get(5, TimeUnit.SECONDS); var b = two.get(5, TimeUnit.SECONDS);
            assertThat(a.run().id()).isEqualTo(b.run().id()); assertThat(a.created()).isNotEqualTo(b.created());
            assertThat(repository.events(actor, session.id(), 0)).hasSize(2);
            assertThatThrownBy(() -> submit(session.id(), "client_request_0001", "changed")).isInstanceOf(AgentProblem.class).hasMessage("同一请求标识不能提交不同内容");
            assertThatThrownBy(() -> submit(session.id(), "client_request_0002", "second")).isInstanceOf(AgentProblem.class).hasMessage("该会话仍在执行任务");
        } finally { executor.shutdownNow(); }
    }
    @Test void toolReceiptReplaysOnceAndFailureRollsBackBusinessChange() throws Exception {
        var session = repository.create(actor, "QA 任务"); var run = repository.claim(actor, submit(session.id(), "client_request_0001", "QA新增").run().id());
        var executions = new AtomicInteger(); var input = json.readTree("{\"operation\":\"create\",\"data\":{\"name\":\"QA商品\"}}");
        var result = repository.mutateOnce(call(run), "tool-1", "forms:1", input, () -> {
            executions.incrementAndGet(); jdbc.update("INSERT INTO qa_business VALUES(1,?)", "QA商品"); return json.createObjectNode().put("id", 1);
        });
        var replay = repository.mutateOnce(call(run), "tool-1", "forms:1", json.readTree("{\"data\":{\"name\":\"QA商品\"},\"operation\":\"create\"}"), () -> { throw new AssertionError("must not repeat"); });
        assertThat(replay).isEqualTo(result); assertThat(executions).hasValue(1);
        assertThatThrownBy(() -> repository.mutateOnce(call(run), "tool-2", "forms:1", input, () -> {
            jdbc.update("INSERT INTO qa_business VALUES(2,?)", "rolled back"); throw new AgentProblem("INVALID_RECORD", "字段校验失败");
        })).isInstanceOf(AgentProblem.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM qa_business", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_agent_tool_receipt", Integer.class)).isEqualTo(1);
        repository.cancel(actor, run.id());
        assertThatThrownBy(() -> repository.mutateOnce(call(run), "tool-3", "forms:1", input, () -> result)).isInstanceOf(AgentProblem.class).hasMessage("任务已停止");
        repository.finish(actor, run.id(), "COMPLETED", null);
        assertThat(repository.run(actor, run.id()).status()).isEqualTo("CANCELLED");
    }
    @Test void historyCursorOwnershipAndRevokedResourcePermissionAreEnforced() {
        var session = repository.create(actor, "QA 任务"); var run = repository.claim(actor, submit(session.id(), "client_request_0001", "QA输入").run().id());
        repository.reference(actor, session.id(), "forms:1");
        repository.append(actor, run.id(), new AgentEventView("TEXT_DELTA", Map.of("delta", "QA结果")));
        var first = repository.events(actor, session.id(), 0);
        assertThat(first).hasSize(4);
        assertThat(repository.events(actor, session.id(), Long.parseLong(first.get(2).id()))).hasSize(1).first()
                .satisfies(event -> assertThat(event.data()).containsEntry("delta", "QA结果"));
        for (AgentActor other : List.of(new AgentActor("brand-a", "43"), new AgentActor("brand-b", "42"))) {
            assertThatThrownBy(() -> repository.session(other, session.id())).isInstanceOf(AgentProblem.class);
            assertThatThrownBy(() -> repository.events(other, session.id(), 0)).isInstanceOf(AgentProblem.class);
            assertThatThrownBy(() -> repository.run(other, run.id())).isInstanceOf(AgentProblem.class);
            assertThatThrownBy(() -> repository.cancel(other, run.id())).isInstanceOf(AgentProblem.class);
        }
        historyAllowed.set(false);
        assertThat(repository.sessions(actor)).isEmpty();
        assertThatThrownBy(() -> repository.events(actor, session.id(), 0)).isInstanceOf(AgentProblem.class).hasMessage("资源授权已变更");
        assertThat(jdbc.queryForList("SELECT payload FROM sys_agent_event", String.class)).allSatisfy(payload -> assertThat(payload).doesNotContain("QA结果", "QA输入"));
    }
    @Test void realWorkerPersistsSdkHistoryAndReconnectDoesNotResubmit() throws Exception {
        var session = repository.create(actor, "QA 会话"); var calls = new AtomicInteger();
        AgentModelGateway model = (context, request) -> {
            int turn = calls.incrementAndGet();
            if (turn > 1) assertThat(request).contains("第一次QA输入", "第一次回答");
            return Flux.just("{\"id\":\"qa-completion\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"第一次回答\"},\"finish_reason\":\"stop\"}]}");
        };
        try (var workbench = new AgentWorkbench(repository, (owner, scene) -> new AgentPromptGateway.Preset("QA系统提示词", model),
                null, jdbc, new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())), codec, json, workspace)) {
            var first = workbench.submit(actor, session.id(), "client_request_0001", "第一次QA输入", "scene.qa");
            awaitTerminal(first.run().id());
            assertThat(repository.run(actor, first.run().id()).status()).isEqualTo("COMPLETED");
            assertThat(repository.events(actor, session.id(), 0)).anySatisfy(event -> assertThat(event.type()).isEqualTo("TEXT_DELTA"));
            assertThat(workbench.submit(actor, session.id(), "client_request_0001", "第一次QA输入", "scene.qa").created()).isFalse();
            repository.events(actor, session.id(), 0); repository.events(actor, session.id(), 0);
            assertThat(calls).hasValue(1);
            var second = workbench.submit(actor, session.id(), "client_request_0002", "继续QA任务", "scene.qa");
            awaitTerminal(second.run().id());
            assertThat(repository.run(actor, second.run().id()).status()).isEqualTo("COMPLETED");
            assertThat(calls).hasValue(2);
        }
    }
    @Test void cancellationStopsSilentProviderAndDoesNotRunAgain() throws Exception {
        var session = repository.create(actor, "QA 取消"); var entered = new CountDownLatch(1); var cancelled = new CountDownLatch(1);
        var signal = new java.util.concurrent.atomic.AtomicReference<String>("not-finalized");
        AgentModelGateway model = (context, request) -> Flux.<String>create(sink -> {
            sink.onCancel(cancelled::countDown); entered.countDown();
        }).doFinally(type -> signal.set(type.name()));
        try (var workbench = new AgentWorkbench(repository, (owner, scene) -> new AgentPromptGateway.Preset("QA系统提示词", model),
                null, jdbc, new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())), codec, json, workspace)) {
            var run = workbench.submit(actor, session.id(), "client_request_0001", "等待QA请求", "scene.qa").run();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            workbench.cancel(actor, run.id());
            assertThat(cancelled.await(3, TimeUnit.SECONDS)).as("provider final=%s, run=%s", signal.get(), repository.run(actor, run.id()).status()).isTrue();
            awaitTerminal(run.id()); assertThat(repository.run(actor, run.id()).status()).isEqualTo("CANCELLED");
        }
    }
    private void awaitTerminal(String run) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end && !Set.of("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED").contains(repository.run(actor, run).status())) Thread.sleep(10);
    }
}
