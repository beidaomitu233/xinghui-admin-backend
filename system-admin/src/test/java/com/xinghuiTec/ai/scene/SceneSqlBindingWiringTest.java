package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.contract.AiSceneDataRuntime;
import com.xinghuiTec.ai.contract.AiSceneQueryRuntime;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** C's compiler verdict is a typed seam fixture; this verifies B's complete immutable transfer only. */
class SceneSqlBindingWiringTest {
    @Test void sqlBindingsReachCompilerPersistAndSurviveBodyAndRouteUpdatesWithoutRecompilationOnRouting()throws Exception {
        try(var f=new SceneTestSupport()) {
            var query=new AiSceneQueryRuntime.SqlQuery("SELECT f.subject FROM form_1 f",Map.of(),20);
            var binding=new AiSceneQueryRuntime.QueryBinding("topics","主题","当前授权主题",List.of("lowcode.1"),query);
            var column=new AiSceneQueryRuntime.QueryColumn("subject","主题","VARCHAR",true);
            var compiledQuery=new AiSceneQueryRuntime.CompiledQuery(AiSceneQueryRuntime.COMPILER_VERSION,query,List.of(new AiSceneQueryRuntime.DatasetSnapshot("lowcode.1","form_1","lowcode-v1",List.of(column),"fixed-schema")),List.of(column),"fixed-query",null);
            var data=mock(AiSceneDataRuntime.class);
            when(data.compile(any(),any(),any())).thenAnswer(call->{
                AiSceneDataRuntime.EditorInput editor=call.getArgument(0);assertEquals(List.of(binding),editor.sqlBindings());
                return new AiSceneDataRuntime.CompiledScene("scene-data-v3",editor,List.of(),List.of(new AiSceneDataRuntime.InputVariable("user_request","用户要求",com.xinghuiTec.ai.contract.AiTemplateRuntime.VariableType.STRING,false,2048)),List.of(),List.of(),List.of(new AiSceneQueryRuntime.QuerySnapshot(binding,compiledQuery)));
            });
            f.beans.addBean("data",data);var created=f.create(false);var defaultPolicy=f.definition("unused",false).modelPolicy();
            var definition=new SceneDtos.Definition("Scene","","{{topics:主题}} {{user_request:用户要求}}","",List.of(),defaultPolicy,List.of(),null,List.of(binding));
            var initial=f.management.save(created.scene().sceneKey(),new SceneDtos.Save(0,UUID.randomUUID().toString(),definition),f.actor);
            assertEquals(List.of(binding),f.management.detail(created.scene().sceneKey(),f.actor).definition().sqlBindings());
            var body=new SceneDtos.Definition("Scene","","Updated {{topics:主题}} {{user_request}}","",List.of(),null,List.of(),null,List.of(binding));
            var updated=f.management.save(created.scene().sceneKey(),new SceneDtos.Save(initial.detail().scene().revision(),UUID.randomUUID().toString(),body),f.actor);
            String before=f.jdbc.queryForObject("SELECT compiled_json FROM sys_ai_scene_version WHERE scene_version_id=?",String.class,updated.detail().scene().currentVersionId());
            var routed=f.management.routing(created.scene().sceneKey(),new SceneRoutingDtos.Change(updated.detail().scene().revision(),UUID.randomUUID().toString(),defaultPolicy),f.actor);
            assertEquals(before,f.jdbc.queryForObject("SELECT compiled_json FROM sys_ai_scene_version WHERE scene_version_id=?",String.class,routed.routing().currentVersionId()));
            assertEquals(List.of(binding),f.management.detail(created.scene().sceneKey(),f.actor).definition().sqlBindings());
            verify(data,times(2)).compile(any(),any(),any());assertEquals(0,f.provider.calls.get());
        }
    }
}
