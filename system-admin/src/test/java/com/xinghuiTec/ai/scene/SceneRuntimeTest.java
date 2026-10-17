package com.xinghuiTec.ai.scene;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SceneRuntimeTest {
    private SceneTestSupport fixture;
    @BeforeEach void setup()throws Exception{fixture=new SceneTestSupport();}
    @AfterEach void close(){fixture.close();}
    @Test void realCompiledSceneUsesExistingWorkerAndSameKeyReplaysOldSnapshotAfterAutosave()throws Exception{
        var saved=fixture.save(fixture.create(false),"First {{user_request}}",false);String key=saved.detail().scene().sceneKey();String nonce=UUID.randomUUID().toString();
        var accepted=fixture.service.submit(key,fixture.input(false,nonce),fixture.actor);assertEquals("SUCCEEDED",fixture.terminal(accepted.requestId()).status());
        var newer=fixture.save(saved.detail(),"Second {{user_request}}",false);assertNotEquals(accepted.sceneVersionId(),newer.detail().scene().currentVersionId());
        var replay=fixture.service.submit(key,fixture.input(false,nonce),fixture.actor);assertEquals(accepted.requestId(),replay.requestId());assertEquals(accepted.sceneVersionId(),replay.sceneVersionId());assertEquals(1,fixture.provider.calls.get());
        assertTrue(fixture.provider.observed.get(0).messages().stream().anyMatch(m->m.content().contains("First")));
        assertNull(fixture.mapper.valueToTree(accepted).get("appId"));
    }
    @Test void versionedTestRemainsAuthorizedAfterAutosaveAndRecordFieldsNeverIncludeInternalNote()throws Exception{
        var saved=fixture.save(fixture.create(true),"Read {{record_subject}}",true);String key=saved.detail().scene().sceneKey();
        var accepted=fixture.service.test(key,new SceneDtos.TestInput(1,0,true,fixture.input(true,UUID.randomUUID().toString())),fixture.actor);
        assertEquals("SUCCEEDED",fixture.terminal(accepted.requestId()).status());fixture.save(saved.detail(),"New {{record_subject}}",true);
        var request=fixture.runtime.status(Long.parseLong(accepted.requestId()),fixture.actor);fixture.runtime.authorizeEvents(request,fixture.actor);
        assertEquals(accepted.sceneVersionId(),request.sceneVersionId());assertNotNull(request.appVersionId());assertNull(request.draftRevision());
        String sent=fixture.provider.observed.get(0).messages().stream().map(m->m.content()).reduce("",String::concat);
        assertTrue(sent.contains("visible subject"));assertFalse(sent.contains("must-never-enter-model"));
    }
    @Test void inFlightRequestDoesNotSwapPromptAndUnauthorizedReferenceFailsBeforeProvider()throws Exception{
        var saved=fixture.save(fixture.create(true),"Original {{record_subject}}",true);String key=saved.detail().scene().sceneKey();
        fixture.provider.entered=new CountDownLatch(1);fixture.provider.release=new CountDownLatch(1);
        var accepted=fixture.service.submit(key,fixture.input(true,UUID.randomUUID().toString()),fixture.actor);assertTrue(fixture.provider.entered.await(2,TimeUnit.SECONDS));
        fixture.save(saved.detail(),"Changed {{record_subject}}",true);fixture.provider.release.countDown();assertEquals("SUCCEEDED",fixture.terminal(accepted.requestId()).status());
        assertTrue(fixture.provider.observed.get(0).messages().stream().anyMatch(m->m.content().contains("Original")));
        var forged=new SceneDtos.Submission(new SceneDtos.BusinessRef("lowcode.record","2:11"),Map.of("user_request","brief"),UUID.randomUUID().toString());
        assertThrows(SceneFailure.class,()->fixture.service.submit(key,forged,fixture.actor));assertEquals(1,fixture.provider.calls.get());
    }
}
