package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.controller.AiApplicationAdminController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Response serialization contract only; actual authentication/filter-chain coverage is separate. */
class AiApplicationHttpContractTest {
    @Test
    void parameterPermissionConflictAndServiceResponsesKeepHttpSeparateFromBusinessCode() {
        var controller = new AiApplicationAdminController(null, null, null, null);
        assertResponse(controller.invalid(), 400, 202, "INVALID_INPUT");
        assertResponse(controller.denied(), 403, 309, "FORBIDDEN");
        assertResponse(controller.runtimeError(new AiRuntimeException(ErrorCode.UNAUTHENTICATED)), 401, 311, "UNAUTHENTICATED");
        assertResponse(controller.runtimeError(new AiRuntimeException(ErrorCode.NOT_FOUND)), 404, 204, "NOT_FOUND");
        assertResponse(controller.runtimeError(new AiRuntimeException(ErrorCode.VERSION_CONFLICT)), 409, 201, "VERSION_CONFLICT");
        assertResponse(controller.runtimeError(new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE)), 503, 203, "DEPENDENCY_UNAVAILABLE");
        assertResponse(controller.runtimeError(new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE)), 422, 202, "MODEL_UNAVAILABLE");
        for (int status : new int[] {413, 415}) assertResponse(AiHttpFailures.response(status, "INVALID_INPUT"), status, 202, "INVALID_INPUT");
        for (int status : new int[] {429, 500, 503, 504}) assertResponse(AiHttpFailures.response(status, "INTERNAL_FAILURE"), status, 203, "INTERNAL_FAILURE");
    }

    private void assertResponse(org.springframework.http.ResponseEntity<com.xinghuiTec.utils.Result<Void>> response,
                                int status, int businessCode, String safeMessage) {
        assertEquals(status, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(businessCode, response.getBody().getCode());
        assertEquals(safeMessage, response.getBody().getMessage());
        assertNull(response.getBody().getData());
    }
}
