package com.xinghuiTec.ai.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import static com.xinghuiTec.ai.contract.AiTemplateRuntime.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class AiContractTest {
    private final ModelVersionRef model = new ModelVersionRef("9007199254740993001", "9007199254740993002");
    private final KeyRef key = new KeyRef("key-1", "key-version-1");
    private final Capabilities text = new Capabilities(Set.of(OutputFormat.TEXT), true, false);
    private final Invocation invocation = new Invocation(new TrustedActor("user", "tenant", Set.of("read"), null),
            "app", "app-v1", new BusinessRef("lowcode-record", "record"), "request");

    @Test void nestedInputsAreCopiedAndCannotBeMutatedAfterAuthorization() {
        var nested = new ArrayList<>(List.of("approved"));
        var source = new LinkedHashMap<String, Object>();
        source.put("fields", nested);
        source.put("optional", null);
        var inputs = new AiInputs(source);
        nested.add("unapproved");
        source.put("injected", "value");
        assertEquals(List.of("approved"), inputs.values().get("fields"));
        assertFalse(inputs.values().containsKey("injected"));
        assertNull(inputs.values().get("optional"));
        assertThrows(UnsupportedOperationException.class, () -> inputs.values().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) inputs.values().get("fields")).clear());
    }

    @Test void rejectsExecutableObjectsNonFiniteNumbersAndRecursiveInput() {
        assertThrows(IllegalArgumentException.class, () -> new AiInputs(Map.of("value", new Object())));
        assertThrows(IllegalArgumentException.class, () -> new AiInputs(Map.of("value", Double.NaN)));
        var recursive = new LinkedHashMap<String, Object>();
        recursive.put("self", recursive);
        assertThrows(IllegalArgumentException.class, () -> new AiInputs(recursive));
    }

    @Test void secretBearingObjectsNeverPrintMessageOrInputContents() {
        String sensitive = "private-business-prompt";
        var messages = new ArrayList<>(List.of(new AiMessage(MessageRole.USER, sensitive)));
        var request = new ExecutionRequest(invocation, "attempt", new Target(model, key, "openrouter",
                "example/model", "Key 1", text), messages, new Requirements(OutputFormat.TEXT, true, false), 128);
        var template = new TemplateVersionRef("template", "template-v1");
        var rendered = new RenderedTemplate(template, messages, List.of(), List.of());
        var inputs = new AiInputs(Map.of("instruction", sensitive));
        for (Object value : List.of(messages.get(0), request, rendered, inputs, new RenderRequest(template, inputs, invocation)))
            assertFalse(value.toString().contains(sensitive));
        messages.clear();
        assertEquals(1, request.messages().size());
        assertEquals(1, rendered.messages().size());
        assertThrows(UnsupportedOperationException.class, () -> request.messages().clear());
    }

    @Test void fixedKeysRequireExplicitCompatibleFallbackAndAutomaticKeysCannotSmuggleOne() {
        assertDoesNotThrow(() -> new ModelBinding(model, KeyMode.AUTO, Set.of(), null, List.of()));
        assertDoesNotThrow(() -> new ModelBinding(model, KeyMode.FIXED, Set.of(key), key, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ModelBinding(model, KeyMode.FIXED, Set.of(), null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ModelBinding(model, KeyMode.AUTO, Set.of(), key, List.of()));
        var other = new KeyRef("other", "v1");
        assertThrows(IllegalArgumentException.class, () -> new ModelBinding(model, KeyMode.FIXED, Set.of(key), key, List.of(other)));
        assertThrows(IllegalArgumentException.class, () -> new ModelBinding(model, KeyMode.FIXED, Set.of(), key, List.of(key)));
    }

    @Test void unknownUsageStaysUnknownAndOutputCannotAuthorizeSilentRetry() {
        var unknown = new Usage(null, null, null);
        assertNull(unknown.totalTokens());
        assertEquals(0L, new Usage(0L, 0L, 0L).totalTokens());
        assertThrows(IllegalArgumentException.class, () -> new Usage(-1L, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOutcome(ExecutionStatus.FAILED,
                ErrorCode.NETWORK_ERROR, true, true, unknown, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOutcome(ExecutionStatus.SUCCEEDED,
                ErrorCode.NETWORK_ERROR, false, true, unknown, null, 1));
    }

    @Test void cancellationAndDeadlineProduceOnlySafeClassifiedErrors() {
        var cancel = new InvocationControl() {
            public Instant deadline() { return Instant.now().plusSeconds(60); }
            public boolean cancelled() { return true; }
        };
        var deadline = new InvocationControl() {
            public Instant deadline() { return Instant.EPOCH; }
            public boolean cancelled() { return false; }
        };
        assertEquals(ErrorCode.CANCELLED, assertThrows(AiRuntimeException.class, cancel::check).code());
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AiRuntimeException.class, deadline::check).code());
        assertEquals("NETWORK_ERROR", new AiRuntimeException(ErrorCode.NETWORK_ERROR).getMessage());
        assertNull(new AiRuntimeException(ErrorCode.NETWORK_ERROR).getCause());
    }

    @Test void exactVersionReferencesAndCapabilityMatchingRemainDistinct() {
        assertNotEquals(model, new ModelVersionRef(model.modelRef(), "next-version"));
        assertEquals("9007199254740993001", model.modelRef());
        assertTrue(text.supports(new Requirements(OutputFormat.TEXT, true, false)));
        assertFalse(text.supports(new Requirements(OutputFormat.JSON_OBJECT, false, false)));
        assertFalse(text.supports(new Requirements(OutputFormat.TEXT, false, true)));
    }
}
