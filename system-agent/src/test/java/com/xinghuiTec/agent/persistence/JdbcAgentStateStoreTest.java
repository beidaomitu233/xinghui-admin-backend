package com.xinghuiTec.agent.persistence;

import com.xinghuiTec.agent.api.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class JdbcAgentStateStoreTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    AgentActor actor = new AgentActor("brand-a", "42");
    AgentPayloadCodec codec = new AgentPayloadCodec() {
        public String seal(String purpose, String plaintext) {
            return Base64.getEncoder().encodeToString((purpose + "\n" + plaintext).getBytes(StandardCharsets.UTF_8));
        }
        public String open(String purpose, String ciphertext) {
            String value = new String(Base64.getDecoder().decode(ciphertext), StandardCharsets.UTF_8);
            if (!value.startsWith(purpose + "\n")) throw new IllegalStateException("Wrong encryption purpose");
            return value.substring(purpose.length() + 1);
        }
    };
    @BeforeEach void database() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261024_400__agent_workbench.sql"),new ClassPathResource("db/migration/V20261025_430__agent_images.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); transactions = new DataSourceTransactionManager(ds);
    }
    private JdbcAgentStateStore store(AgentActor owner, Runnable guard) {
        return new JdbcAgentStateStore(jdbc, transactions, owner, "session-1", codec, guard);
    }
    public record Sample(String text, boolean flag) implements State {}

    @Test void persistsSdkMessagesAndListsWithoutCrossTenantAccess() {
        var store = store(actor, () -> {}); String user = actor.stateUserId();
        store.save(user, "session-1", "sample", new Sample("QA私有数据", false));
        store.save(user, "session-1", "messages", List.of(new UserMessage("QA用户消息")));
        assertThat(store.get(user, "session-1", "sample", Sample.class)).contains(new Sample("QA私有数据", false));
        assertThat(store.getList(user, "session-1", "messages", Msg.class)).hasSize(1)
                .first().satisfies(message -> assertThat(message.getTextContent()).isEqualTo("QA用户消息"));
        assertThat(store.listSessionIds(user)).containsExactly("session-1");
        var otherActor = new AgentActor("brand-b", "42");
        var other = store(otherActor, () -> {});
        assertThat(other.get(otherActor.stateUserId(), "session-1", "sample", Sample.class)).isEmpty();
        assertThatThrownBy(() -> store.get(otherActor.stateUserId(), "session-1", "sample", Sample.class))
                .isInstanceOf(AgentProblem.class).hasMessage("无权访问该会话");
        assertThatThrownBy(() -> store.get(user, "someone-elses-session", "sample", Sample.class)).isInstanceOf(AgentProblem.class);
        assertThat(jdbc.queryForList("SELECT payload FROM sys_agent_state", String.class)).allSatisfy(value ->
                assertThat(value).doesNotContain("QA私有数据", "QA用户消息"));
    }
    @Test void compareAndSwapAndStoppedRunGuardProtectPersistedState() {
        var allowed = new AtomicBoolean(true);
        var store = store(actor, () -> { if (!allowed.get()) throw new AgentProblem("RUN_STOPPED", "任务已停止"); });
        String user = actor.stateUserId();
        assertThat(store.saveIfVersion(user, "session-1", "s", new Sample("first", true), 0)).isEqualTo(1);
        assertThat(store.saveIfVersion(user, "session-1", "s", new Sample("duplicate", false), 0)).isEqualTo(-1);
        assertThat(store.saveIfVersion(user, "session-1", "s", new Sample("next", false), 1)).isEqualTo(2);
        assertThat(store.saveIfVersion(user, "session-1", "s", new Sample("stale", true), 1)).isEqualTo(-1);
        allowed.set(false);
        assertThatThrownBy(() -> store.save(user, "session-1", "s", new Sample("late", true))).isInstanceOf(AgentProblem.class);
        assertThatThrownBy(() -> store.delete(user, "session-1")).isInstanceOf(AgentProblem.class);
        assertThat(store.getVersioned(user, "session-1", "s", Sample.class).value()).isEqualTo(new Sample("next", false));
        assertThat(store.getVersioned(user, "session-1", "s", Sample.class).version()).isEqualTo(2);
    }
}
