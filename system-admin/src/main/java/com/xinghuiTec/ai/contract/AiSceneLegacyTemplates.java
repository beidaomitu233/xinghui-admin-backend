package com.xinghuiTec.ai.contract;

import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import java.util.List;

/** C-owned legacy template bridge. B supplies the exact mapped legacy invocation, never client app IDs. */
public interface AiSceneLegacyTemplates {
    LegacyView inspect(TemplateVersionRef version, Invocation legacy, InvocationControl control);
    CompiledScene compileEdited(TemplateVersionRef version, Invocation legacy, EditorInput edited, InvocationControl control);
    enum Compatibility { CONVERTIBLE, READ_ONLY }
    record LegacyMessage(String section,String text) {
        @Override public String toString(){return "LegacyMessage[REDACTED]";}
    }
    record LegacyView(TemplateVersionRef version,String name,Compatibility compatibility,List<String> reasons,
                      boolean requiresConfirmation,List<String> conversionNotes,EditorInput editor,List<LegacyMessage> originalMessages,
                      AiInputs originalDefinition,List<LegacyScope> scopes) {
        public LegacyView {reasons=List.copyOf(reasons);conversionNotes=List.copyOf(conversionNotes);originalMessages=List.copyOf(originalMessages);scopes=List.copyOf(scopes);}
        @Override public String toString(){return "LegacySceneView[REDACTED]";}
    }
}
