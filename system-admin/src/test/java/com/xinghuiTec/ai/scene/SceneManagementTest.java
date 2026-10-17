package com.xinghuiTec.ai.scene;

import org.junit.jupiter.api.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SceneManagementTest {
    private SceneTestSupport fixture;
    @BeforeEach void setup()throws Exception{fixture=new SceneTestSupport();}
    @AfterEach void close(){fixture.close();}
    @Test void lostCreateResponseReplaysOriginalIdentityWithoutLaterPromptAndDifferentBodyConflicts(){
        String mutation=UUID.randomUUID().toString();var input=new SceneDtos.Create("Original","",SceneDtos.Kind.GENERAL,null,mutation);
        var one=fixture.management.create(input,fixture.actor);fixture.save(one,"Later sensitive configured prompt",false);
        var addOnly=new com.xinghuiTec.ai.contract.AiTypes.TrustedActor(fixture.actor.subjectId(),fixture.actor.tenantId(),java.util.Set.of("ai:scene:add"),null);
        var replay=fixture.management.create(input,addOnly);assertEquals(one,replay);assertNull(replay.definition());assertEquals("Original",replay.scene().name());
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene",Integer.class));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(SceneFailure.class,()->fixture.management.create(new SceneDtos.Create("Different","",SceneDtos.Kind.GENERAL,null,mutation),fixture.actor)).getMessage());
        var recordInput=new SceneDtos.Create("Record","",SceneDtos.Kind.RECORD_SUMMARY,"1",UUID.randomUUID().toString());
        assertEquals(fixture.management.create(recordInput,fixture.actor),fixture.management.create(recordInput,fixture.actor));
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene",Integer.class));
    }
    @Test void completeSaveImmediatelySelectsRealSnapshotWithoutTestsOrProviderCallsAndPreservesDisabledIntent(){
        var created=fixture.create(false);assertNull(created.scene().currentVersionId());
        var saved=fixture.save(created,"First {{user_request}}",false);assertNotNull(saved.detail().scene().currentVersionId());
        assertEquals(0,fixture.provider.calls.get());assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request",Integer.class));
        assertNull(fixture.jdbc.queryForObject("SELECT tested_request_id FROM sys_ai_application_version",Long.class));
        assertEquals("SCENE_SAVE",fixture.jdbc.queryForObject("SELECT snapshot_source FROM sys_ai_application_version",String.class));
        var disabled=fixture.management.enabled(created.scene().sceneKey(),new SceneDtos.Enabled(1,UUID.randomUUID().toString(),false),fixture.actor);
        var changed=fixture.save(disabled.detail(),"Second {{user_request}}",false);assertFalse(changed.detail().scene().enabled());
        assertEquals(SceneDtos.AvailabilityState.DISABLED,changed.detail().scene().availability().state());
    }
    @Test void invalidPromptAndStaleCasDoNotPersistIntermediateContentAndMutationReplayDoesNotDuplicateVersions(){
        var created=fixture.create(false);var saved=fixture.save(created,"Valid",false);var head=saved.detail();
        var bad=assertThrows(SceneFailure.class,()->fixture.save(head,"{{unknown_variable}}",false));assertEquals(400,bad.status());
        assertEquals("definition.promptText",bad.details().fields().get(0).field());
        assertEquals(1,fixture.management.detail(head.scene().sceneKey(),fixture.actor).scene().revision());
        assertThrows(SceneFailure.class,()->fixture.save(created,"Late",false));
        String mutation=UUID.randomUUID().toString();var input=new SceneDtos.Save(1,mutation,fixture.definition("New",false));
        var one=fixture.management.save(head.scene().sceneKey(),input,fixture.actor);var two=fixture.management.save(head.scene().sceneKey(),input,fixture.actor);
        assertEquals(one,two);assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene_version",Integer.class));
        assertThrows(SceneFailure.class,()->fixture.management.save(head.scene().sceneKey(),new SceneDtos.Save(1,mutation,fixture.definition("Changed",false)),fixture.actor));
    }
    @Test void unavailableModelStillSavesAndLegacyWritePathsCannotModifyOwnedExecution(){
        fixture.provider.unavailable=true;var saved=fixture.save(fixture.create(false),"Valid",false);
        assertEquals(SceneDtos.AvailabilityState.UNAVAILABLE,saved.detail().scene().availability().state());assertEquals(0,fixture.provider.calls.get());
        var head=fixture.scenes.head(saved.detail().scene().sceneKey(),false);
        var old=fixture.applications.get(Long.parseLong(head.appId()));
        var failure=assertThrows(SceneFailure.class,()->fixture.applications.updateDraft(Long.parseLong(head.appId()),old.revision(),new com.xinghuiTec.ai.application.AiApplicationStore.Draft(old.name(),"old",old.entryKey(),old.draft())));
        assertEquals("SCENE_MANAGED",failure.getMessage());
        assertThrows(SceneFailure.class,()->fixture.versions.publish(Long.parseLong(head.appId()),old.revision(),java.util.List.of(),fixture.actor));
    }
}
