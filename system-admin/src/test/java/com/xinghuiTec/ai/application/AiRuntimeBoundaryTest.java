package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.controller.AiRuntimeController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Local runtime body/filter/response contracts. Not a JWT/real-service or browser acceptance test. */
class AiRuntimeBoundaryTest {
    private final AiRuntimeBodyCodec codec = new AiRuntimeBodyCodec();
    private static final String BODY = "{\"businessRef\":{\"type\":\"lowcode.record\",\"id\":\"1:7\"},"
            + "\"inputs\":{\"user_request\":\"neutral fixture\"},\"idempotencyKey\":\"0e925dce-e78b-442e-9bf0-5ea7b019508b\"}";

    @Test
    void jsonCodecRejectsIdentityAdditionsDuplicatesTrailingNullAndOverDepthWithoutEcho() {
        assertEquals("1:7", codec.read(bytes(BODY), AiRuntimeService.Submission.class).businessRef().id());
        for (String malformed : new String[] {
                "null", "[]", BODY + "{}", BODY.replace("\"inputs\":", "\"actor\":{\"userId\":999},\"inputs\":"),
                BODY.replace("\"neutral fixture\"", "\"secret fixture\",\"user_request\":\"duplicate\""),
                BODY.replace("\"neutral fixture\"", "[".repeat(10) + "0" + "]".repeat(10)),
                BODY.replace("neutral fixture", "s".repeat(8193))
        }) {
            var error = assertThrows(AiRuntimeException.class, () -> codec.read(bytes(malformed), AiRuntimeService.Submission.class));
            assertEquals(ErrorCode.INVALID_INPUT, error.code());
            assertEquals("INVALID_INPUT", error.getMessage());
        }
        assertThrows(AiRuntimeException.class, () -> codec.read(new byte[32_769], AiRuntimeService.Submission.class));
    }

    @Test
    void knownAndUnknownBodyLengthsAreBoundedBeforeTheAuthenticatedChain() throws Exception {
        for (boolean unknown : new boolean[] {false, true}) {
            MockHttpServletRequest request = unknown ? new MockHttpServletRequest("POST", "/ai/applications/1/requests") {
                @Override public int getContentLength() { return -1; }
                @Override public long getContentLengthLong() { return -1; }
            } : new MockHttpServletRequest("POST", "/ai/applications/1/requests");
            request.setContentType("application/json"); request.setContent(bytes("sensitive-fixture".repeat(3000)));
            request.addHeader("X-Request-ID", "caller-selected");
            var response = new MockHttpServletResponse();
            var reached = new AtomicBoolean();
            new AiRuntimeBoundaryFilter(new ObjectMapper()).doFilter(request, response, (ignoredRequest, ignoredResponse) -> reached.set(true));
            assertFalse(reached.get()); assertEquals(413, response.getStatus());
            assertEquals(202, new ObjectMapper().readTree(response.getContentAsByteArray()).path("code").asInt());
            assertFalse(response.getContentAsString().contains("sensitive-fixture"));
            assertNotNull(UUID.fromString(response.getHeader("X-Request-ID")));
            assertEquals("no-store", response.getHeader("Cache-Control"));
        }
    }

    @Test
    void unsupportedEncodingIsRejectedAndValidBytesArePassedAsBoundedEnvelope() throws Exception {
        var filter = new AiRuntimeBoundaryFilter(new ObjectMapper());
        var compressed = new MockHttpServletRequest("POST", "/ai/applications/1/requests");
        compressed.setContentType("application/json"); compressed.addHeader("Content-Encoding", "gzip");
        var rejected = new MockHttpServletResponse();
        filter.doFilter(compressed, rejected, (request, response) -> fail("Must reject before context lookup"));
        assertEquals(415, rejected.getStatus());
        assertEquals(202, new ObjectMapper().readTree(rejected.getContentAsByteArray()).path("code").asInt());
        var valid = new MockHttpServletRequest("POST", "/ai/applications/1/requests");
        valid.setContentType("application/json"); valid.setContent(bytes(BODY));
        var response = new MockHttpServletResponse();
        filter.doFilter(valid, response, (request, reply) -> assertArrayEquals(bytes(BODY), (byte[]) request.getAttribute(AiRuntimeBoundaryFilter.BODY)));
        assertNotNull(UUID.fromString(response.getHeader("X-Request-ID")));
    }

    @Test
    void runtimeErrorsUseExistingBusinessCodesAndKeepAcceptedFailureTraceOutOfData() {
        var controller = new AiRuntimeController(null, null, codec, null, null);
        var response = new MockHttpServletResponse();
        var failure = controller.failedAdmission(new AiRuntimeService.AdmissionFailure("123", ErrorCode.RESOURCE_TIMEOUT), response);
        assertEquals(504, failure.getStatusCode().value());
        assertEquals(203, failure.getBody().getCode());
        assertEquals("RESOURCE_TIMEOUT", failure.getBody().getMessage());
        assertNull(failure.getBody().getData());
        assertEquals("123", response.getHeader("X-Request-ID"));
        assertEquals(309, controller.denied().getBody().getCode());
        assertEquals(202, controller.invalid().getBody().getCode());
        assertEquals(201, controller.safe(new AiRuntimeException(ErrorCode.VERSION_CONFLICT)).getBody().getCode());
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
