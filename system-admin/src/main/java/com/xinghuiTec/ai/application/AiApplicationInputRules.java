package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiInputs;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.BusinessRef;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;

import java.util.Map;

/** Bounded correlation references are not resource read grants. C remains the data authority. */
final class AiApplicationInputRules {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AiApplicationInputRules() {}

    static void validate(AiApplicationPolicy policy, BusinessRef business, Map<String, Object> inputs) {
        if (business == null || !business.type().matches("[a-z][a-z0-9_.-]{0,63}")
                || !business.id().matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")
                || inputs == null || inputs.size() > 32) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        if (policy.formId() != null) {
            String[] ids = business.id().split(":", -1);
            if (!business.type().equals("lowcode.record") || ids.length != 2 || !policy.formId().equals(ids[0]))
                throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            AiApplicationService.positiveId(ids[0]); AiApplicationService.positiveId(ids[1]);
        }
        try {
            if (JSON.writeValueAsBytes(new AiInputs(inputs).values()).length > 16_384)
                throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        }
    }
}
