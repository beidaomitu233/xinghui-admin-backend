package com.xinghuiTec.ai.contract;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AiTemplatePreviewContractTest {
    @Test void snapshotCopiesCollectionsAndToStringDoesNotExposeMessagesOrSummaries() {
        var messages=new ArrayList<AiMessage>(); messages.add(new AiMessage(MessageRole.SYSTEM,"private-original"));
        var inputs=new HashMap<String,String>(); inputs.put("input","SUPPLIED_MASKED");
        var preview=new AiTemplatePreviewRuntime.PublishedPreview(new TemplateVersionRef("1","2"),messages,messages,inputs,Map.of(),List.of());
        messages.clear(); inputs.clear();
        assertEquals(1,preview.originalMessages().size()); assertEquals(1,preview.renderedMessages().size()); assertEquals("SUPPLIED_MASKED",preview.inputSummary().get("input"));
        assertThrows(UnsupportedOperationException.class,()->preview.originalMessages().clear());
        assertThrows(UnsupportedOperationException.class,()->preview.inputSummary().clear());
        assertEquals("PublishedPreview[REDACTED]",preview.toString());
        assertFalse(preview.originalMessages().toString().contains("private-original"));
    }
}
