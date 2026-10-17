package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.AiSceneRequestAccess;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** No dependency on management/runtime orchestrators: worker and SSE can reauthorize without a cycle. */
@Service
public class SceneRequestAccessService implements AiSceneRequestAccess {
    private final SceneStore store;
    private final ObjectProvider<AiSceneDataRuntime> data;
    public SceneRequestAccessService(SceneStore store,ObjectProvider<AiSceneDataRuntime> data){this.store=store;this.data=data;}
    public Execution execution(String applicationVersionId){var version=store.applicationVersion(applicationVersionId);return version==null || version.compiled()==null?null:new Execution(version.sceneKey(),version.id(),version.compiled());}
    public AiSceneDataRuntime.RenderedScene render(Execution scene,Invocation invocation,AiInputs inputs,InvocationControl control){check(scene,invocation);return runtime().render(scene.compiled(),context(scene,invocation),inputs,control);}
    public void authorize(Execution scene,Invocation invocation,InvocationControl control){check(scene,invocation);runtime().authorize(scene.compiled(),context(scene,invocation),control);}
    private void check(Execution scene,Invocation invocation){
        var actor=invocation.actor();
        if(actor.servicePrincipalRef()!=null || !actor.authorities().contains("ai:scene:invoke"))throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        if(!store.head(scene.sceneKey(),false).enabled())throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
    }
    private AiSceneDataRuntime runtime(){var runtime=data.getIfUnique();if(runtime==null)throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);return runtime;}
    private AiSceneDataRuntime.SceneContext context(Execution scene,Invocation invocation){return new AiSceneDataRuntime.SceneContext(scene.sceneKey(),scene.sceneVersionId(),invocation.actor(),invocation.businessRef(),invocation.requestId());}
}
