package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SceneTestReplayTest {
    private SceneTestSupport f;
    @BeforeEach void setup()throws Exception{f=new SceneTestSupport();}
    @AfterEach void close(){f.close();}
    @Test void facadeReplaysLostTestResponseAfterAutosaveWithoutDispatchOrNewRevisionBypass()throws Exception{
        var first=f.save(f.create(false),"Original {{user_request}}",false);String key=first.detail().scene().sceneKey();
        var original=new SceneDtos.TestInput(1,0,true,f.input(false,UUID.randomUUID().toString()));
        var accepted=f.service.test(key,original,f.actor);assertEquals("SUCCEEDED",f.terminal(accepted.requestId()).status());
        // New configuration even requires a record; replay must use the admitted snapshot rather than normalize against this editor.
        f.save(first.detail(),"Now {{record_subject}}",true);
        var replay=f.service.test(key,original,f.actor);
        assertEquals(accepted.requestId(),replay.requestId());assertEquals(accepted.sceneVersionId(),replay.sceneVersionId());assertEquals(1,f.provider.calls.get());
        var changedBody=new SceneDtos.Submission(null,Map.of("user_request","different"),original.submission().idempotencyKey());
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(AiRuntimeException.class,()->f.service.test(key,new SceneDtos.TestInput(1,0,true,changedBody),f.actor)).code());
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(AiRuntimeException.class,()->f.service.test(key,new SceneDtos.TestInput(1,1,true,original.submission()),f.actor)).code());
        assertEquals("VERSION_CONFLICT",assertThrows(SceneFailure.class,()->f.service.test(key,new SceneDtos.TestInput(1,0,true,f.input(false,UUID.randomUUID().toString())),f.actor)).getMessage());
        var other=new TrustedActor("102","tenant_b",Set.of("ai:scene:invoke"),null);
        assertEquals("VERSION_CONFLICT",assertThrows(SceneFailure.class,()->f.service.test(key,original,other)).getMessage());
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request",Integer.class));assertEquals(1,f.provider.calls.get());
    }
    @Test void recordReplayDoesNotReadBodyAgainButRechecksCurrentRecordAndInvocationAccess()throws Exception{
        var first=f.save(f.create(true),"Original {{record_subject}}",true);String key=first.detail().scene().sceneKey();
        var original=new SceneDtos.TestInput(1,0,true,f.input(true,UUID.randomUUID().toString()));
        var accepted=f.service.test(key,original,f.actor);assertEquals("SUCCEEDED",f.terminal(accepted.requestId()).status());
        f.save(first.detail(),"New {{record_subject}}",true);
        f.jdbc.update("UPDATE sys_lowcode_record SET data_json=? WHERE id=11","{unreadable-record-body");
        assertEquals(accepted.requestId(),f.service.test(key,original,f.actor).requestId());
        var noRecordRight=new TrustedActor("101","tenant_a",Set.of("ai:scene:invoke"),null);
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->f.service.test(key,original,noRecordRight)).code());
        var noInvoke=new TrustedActor("101","tenant_a",Set.of("lowcode:record:query"),null);
        assertEquals(403,assertThrows(SceneFailure.class,()->f.service.test(key,original,noInvoke)).status());
        f.jdbc.update("UPDATE sys_lowcode_record SET is_deleted=1 WHERE id=11");
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->f.service.test(key,original,f.actor)).code());
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request",Integer.class));assertEquals(1,f.provider.calls.get());
    }
}
