package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiApplicationInputRulesTest {
    private AiApplicationPolicy policy(String formId) {
        return new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED, List.of(), false, null, Set.of("text"),
                new Requirements(OutputFormat.TEXT, true, false), AiApplicationPolicy.Scope.FRONTEND,
                Set.of("101"), Set.of(), formId, 30, 2, 2, 100);
    }

    @Test void ordinaryReferencesAreBoundedCorrelationsWithoutAFormRequirement() {
        assertDoesNotThrow(() -> AiApplicationInputRules.validate(policy(null), new BusinessRef("business.rewrite", "job-ABC:17"), Map.of("text", "input")));
        for (BusinessRef ref : List.of(new BusinessRef("a".repeat(65), "1"), new BusinessRef("business.rewrite", "a".repeat(129)),
                new BusinessRef("Bad Type", "1"), new BusinessRef("business.rewrite", "row\n1"), new BusinessRef("business.rewrite", " row"))) {
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AiRuntimeException.class,
                    () -> AiApplicationInputRules.validate(policy(null), ref, Map.of())).code());
        }
        assertDoesNotThrow(() -> AiApplicationInputRules.validate(policy(null), new BusinessRef("a".repeat(64), "1".repeat(128)), Map.of()));
    }

    @Test void explicitSummaryStillRequiresItsActualFormAndPositiveLongRecord() {
        assertDoesNotThrow(() -> AiApplicationInputRules.validate(policy("7"), new BusinessRef("lowcode.record", "7:8"), Map.of()));
        for (BusinessRef ref : List.of(new BusinessRef("business.rewrite", "7:8"), new BusinessRef("lowcode.record", "9:8"),
                new BusinessRef("lowcode.record", "7:0"), new BusinessRef("lowcode.record", "7:9223372036854775808"), new BusinessRef("lowcode.record", "7:8:9"))) {
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AiRuntimeException.class,
                    () -> AiApplicationInputRules.validate(policy("7"), ref, Map.of())).code());
        }
    }

    @Test void bothModesKeepInputCountAndByteBoundsAndReservedSummaryEntryBinding() {
        var tooMany = new HashMap<String, Object>();
        for (int index = 0; index < 33; index++) tooMany.put("v" + index, "value");
        for (String form : Arrays.asList(null, "7")) {
            var ref = new BusinessRef(form == null ? "business.rewrite" : "lowcode.record", form == null ? "job-1" : "7:8");
            for (var inputs : List.of(tooMany, Map.<String, Object>of("text", "x".repeat(16_384))))
                assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AiRuntimeException.class,
                        () -> AiApplicationInputRules.validate(policy(form), ref, inputs)).code());
        }
        assertThrows(AiRuntimeException.class, () -> new AiApplicationStore.Draft("ordinary", "rewrite", "lowcode.record.summary/7", policy(null)));
        assertDoesNotThrow(() -> new AiApplicationStore.Draft("ordinary", "rewrite", "business.rewrite", policy(null)));
    }
}
