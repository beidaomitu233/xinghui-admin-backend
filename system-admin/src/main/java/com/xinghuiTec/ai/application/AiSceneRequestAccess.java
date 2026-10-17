package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiInputs;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime;
import com.xinghuiTec.ai.contract.AiTypes.*;

/** B's runtime adapter; C remains the only compiler/authorized business-data reader. */
public interface AiSceneRequestAccess {
    record Execution(String sceneKey,String sceneVersionId,AiSceneDataRuntime.CompiledScene compiled) {}
    Execution execution(String applicationVersionId);
    AiSceneDataRuntime.RenderedScene render(Execution scene,Invocation invocation,AiInputs inputs,InvocationControl control);
    void authorize(Execution scene,Invocation invocation,InvocationControl control);
}
