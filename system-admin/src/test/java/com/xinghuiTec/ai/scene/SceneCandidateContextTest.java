package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SceneCandidateContextTest {
    @Test void recordQueryWithoutListCannotAdvertiseAUsableContextPicker() throws Exception {
        try (var f = new SceneTestSupport()) {
            f.save(f.create(true), "Private prompt {{record_subject}}", true);
            login(f.actor);
            f.service.platformAccess(new PlatformAccessService(false));
            var allowed = f.service.candidates(SceneDtos.Kind.RECORD_SUMMARY, "1", f.actor).get(0);
            assertTrue(allowed.canStartFromMonitor());
            assertEquals("lowcode.1", allowed.contextSourceKey());
            var rights = new HashSet<>(f.actor.authorities());
            rights.remove("lowcode:record:list");
            var actor = new TrustedActor("101", "tenant_a", rights, null);
            login(actor);
            f.service.platformAccess(new PlatformAccessService(false));
            var candidates = f.service.candidates(SceneDtos.Kind.RECORD_SUMMARY, "1", actor);
            assertEquals(1, candidates.size());
            var entry = candidates.get(0);
            assertTrue(entry.contextRequired());
            assertFalse(entry.canStartFromMonitor());
            assertNull(entry.contextSourceKey());
            assertNull(entry.contextSourceLabel());
            assertFalse(f.mapper.writeValueAsString(entry).contains("Private prompt"));
            assertEquals(0, f.provider.calls.get());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test void tableBindingCannotAdvertiseReadyCandidateWithoutDatabaseReadPermission() throws Exception {
        try (var f = new SceneTestSupport()) {
            f.jdbc.update("INSERT INTO sys_user VALUES(201,'000000',1,0)");
            f.jdbc.execute("CREATE TABLE business_customer(id BIGINT PRIMARY KEY,name VARCHAR(80),tenant_id VARCHAR(64),is_deleted INT DEFAULT 0)");
            var rights = Set.of("ai:scene:invoke", "ai:scene:query", "ai:scene:edit", "ai:data:database:read");
            var admin = new TrustedActor("201", "000000", rights, null);
            var created = f.management.create(new SceneDtos.Create("Table scene", "", SceneDtos.Kind.GENERAL, null, UUID.randomUUID().toString()), admin);
            var binding = new DataBinding("客户", "客户", "db.business_customer", ReadMode.TABLE, List.of("id", "name"), List.of(), 10, null, null);
            f.management.save(created.scene().sceneKey(), new SceneDtos.Save(0, UUID.randomUUID().toString(),
                    new SceneDtos.Definition("Table scene", "", "Read {{客户}}", "", List.of(), f.definition("unused", false).modelPolicy(), List.of(binding))), admin);
            var noRead = new TrustedActor("201", "000000", Set.of("ai:scene:invoke", "ai:scene:query"), null);
            login(noRead);
            f.service.platformAccess(new PlatformAccessService(false));
            assertTrue(f.service.candidates(SceneDtos.Kind.GENERAL, null, noRead).isEmpty());
            assertEquals(0, f.provider.calls.get());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void login(TrustedActor actor) {
        var user = new SysUser();
        user.setUserId(Long.parseLong(actor.subjectId()));
        user.setTenantId(actor.tenantId());
        user.setStatus(1);
        var principal = new LoginUser(user, new ArrayList<>(actor.authorities()));
        principal.setTenantId(actor.tenantId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
