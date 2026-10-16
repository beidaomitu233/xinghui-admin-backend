package com.xinghuiTec.ai.contract;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.xinghuiTec.ai.contract.AiTypes.*;

/**
 * C implements read-only, exact published-version management previews. B must first authorize the
 * actual actor and application draft binding; this is not an arbitrary-template HTTP read grant.
 * No model call, request/attempt creation, or successful model-test evidence is produced.
 */
public interface AiTemplatePreviewRuntime {
    PublishedPreview previewPublished(AiTemplateRuntime.RenderRequest request, InvocationControl control);

    /** Summary keys are declared input/resource names; values are safe classifications, never values. */
    record PublishedPreview(TemplateVersionRef version, List<AiMessage> originalMessages,
                            List<AiMessage> renderedMessages, Map<String, String> inputSummary,
                            Map<String, String> resourceSummary, List<AiTemplateRuntime.RenderNotice> notices) {
        public PublishedPreview {
            Objects.requireNonNull(version);
            originalMessages = List.copyOf(originalMessages);
            renderedMessages = List.copyOf(renderedMessages);
            inputSummary = Map.copyOf(inputSummary);
            resourceSummary = Map.copyOf(resourceSummary);
            notices = List.copyOf(notices);
        }
        @Override public String toString() { return "PublishedPreview[REDACTED]"; }
    }
}
