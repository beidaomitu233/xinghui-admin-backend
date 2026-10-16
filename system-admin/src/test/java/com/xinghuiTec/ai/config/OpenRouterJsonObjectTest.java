package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled upstream protocol tests, not real OpenRouter generation evidence. */
class OpenRouterJsonObjectTest {
    static final String MODEL="openai/gpt-4o-mini";
    final ObjectMapper json=new ObjectMapper();
    HttpClient client; OpenRouterTransport transport; StringBuilder output; int deltas;
    final InvocationControl control=new InvocationControl() {
        public Instant deadline() { return Instant.now().plusSeconds(5); }
        public boolean cancelled() { return false; }
    };
    @BeforeEach void setup() {
        client=mock(HttpClient.class); transport=new OpenRouterTransport(json,new AiModelConfigRules(),client);
        output=new StringBuilder(); deltas=0;
    }
    void response(String body) {
        HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
    }
    String envelope(String content,String finish,boolean streaming) throws Exception {
        return json.writeValueAsString(Map.of("model",MODEL,"choices",List.of(Map.of(streaming?"delta":"message",Map.of("content",content),"finish_reason",finish))));
    }
    ExecutionOutcome call(boolean streaming) {
        return transport.call(MODEL,5,"controlled-secret",List.of(new AiMessage(MessageRole.USER,"short neutral JSON test")),new Requirements(OutputFormat.JSON_OBJECT,streaming,false),128,new StreamObserver() {
            public void onDelta(String text) { deltas++; output.append(text); }
            public void onUsage(Usage usage) {}
        },control);
    }
    String requestBody(HttpRequest request) throws Exception {
        var result=new CompletableFuture<String>(); var bytes=new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] item=new byte[buffer.remaining()]; buffer.get(item); bytes.writeBytes(item); }
            public void onError(Throwable failure) { result.completeExceptionally(failure); }
            public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.get(1,TimeUnit.SECONDS);
    }
    @Test void sendsJsonObjectAndRequiredParameterRoutingThenReturnsOneValidatedObject() throws Exception {
        response(envelope("{\"summary\":\"ok\",\"items\":[1,true]}","stop",false));
        assertEquals(ExecutionStatus.SUCCEEDED,call(false).status());
        assertTrue(json.readTree(output.toString()).isObject()); assertEquals(1,deltas);
        var captured=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client,times(1)).sendAsync(captured.capture(),any(HttpResponse.BodyHandler.class));
        var body=json.readTree(requestBody(captured.getValue()));
        assertEquals("json_object",body.path("response_format").path("type").asText());
        assertTrue(body.path("provider").path("require_parameters").asBoolean());
        assertFalse(body.path("provider").path("allow_fallbacks").asBoolean());
        assertEquals("system",body.path("messages").path(0).path("role").asText());
        assertFalse(body.has("json_schema"));
    }
    @Test void rejectsNonObjectTrailingDuplicateMalformedAndOverNestedValuesWithoutDelivery() throws Exception {
        for(String invalid:List.of("[]","null","1","\"text\"","{} {}","{","```json\n{}\n```","{\"a\":1,\"a\":2}","{\"a\":".repeat(65)+"0"+"}".repeat(65))) {
            response(envelope(invalid,"stop",false));
            var result=call(false); assertEquals(ExecutionStatus.INCOMPLETE,result.status());
            assertEquals(ErrorCode.INVALID_RESPONSE,result.errorCode()); assertFalse(result.retryable());
            assertTrue(result.outputStarted()); assertEquals("",output.toString());
        }
        for(String invalidEnvelope:List.of("{",envelope("{}","stop",false)+"{}")) {
            response(invalidEnvelope); var result=call(false);
            assertEquals(ErrorCode.INVALID_RESPONSE,result.errorCode()); assertFalse(result.retryable()); assertTrue(result.outputStarted());
        }
    }
    @Test void upstreamStreamingIsBoundedAndDeliveredOnlyAfterCompleteObjectValidation() throws Exception {
        response("data: "+envelope("{\"summary\":","",true)+"\n\ndata: "+envelope("\"ok\"}","stop",true)+"\n\ndata: [DONE]\n\n");
        var result=call(true); assertEquals(ExecutionStatus.SUCCEEDED,result.status());
        assertEquals("ok",json.readTree(output.toString()).path("summary").asText()); assertEquals(1,deltas);
    }
    @Test void eofTruncationAndProviderLengthAreFailuresWithoutStructuredOutputOrSilentRetry() throws Exception {
        for(String body:List.of("data: "+envelope("{\"a\":1}","stop",true)+"\n\n",
                "data: "+envelope("{\"a\":","stop",true)+"\n\ndata: [DONE]\n\n",
                "data: "+envelope("{\"a\":","length",true)+"\n\ndata: [DONE]\n\n")) {
            response(body); var result=call(true);
            assertEquals(ExecutionStatus.INCOMPLETE,result.status()); assertFalse(result.retryable()); assertTrue(result.outputStarted());
            assertEquals("",output.toString());
        }
    }
    @Test void decodesEscapedSecretsBeforeScrubbingValuesAndPropertyNames() throws Exception {
        response(envelope("{\"controlled-\\u0073ecret\":\"controlled-\\u0073ecret\"}","stop",false));
        assertEquals(ExecutionStatus.SUCCEEDED,call(false).status());
        assertEquals("[redacted]",json.readTree(output.toString()).path("[redacted]").asText());
        assertFalse(output.toString().contains("controlled"));
    }
    @Test void observerFailureAfterDeliveryIsIncompleteAndCannotRetry() throws Exception {
        response("data: "+envelope("{\"ok\":true}","stop",true)+"\n\ndata: [DONE]\n\n");
        var observer=mock(StreamObserver.class); doThrow(new IllegalStateException("private observer failure")).when(observer).onDelta(anyString());
        var result=transport.call(MODEL,5,"controlled-secret",List.of(new AiMessage(MessageRole.USER,"JSON test")),new Requirements(OutputFormat.JSON_OBJECT,true,false),128,observer,control);
        assertEquals(ExecutionStatus.INCOMPLETE,result.status()); assertTrue(result.outputStarted()); assertFalse(result.retryable());
    }
    @Test void unknownModelCannotClaimJsonCapabilityOrReachTransport() {
        var result=transport.call("unknown/model",5,"controlled-secret",List.of(new AiMessage(MessageRole.USER,"test")),new Requirements(OutputFormat.JSON_OBJECT,false,false),128,mock(StreamObserver.class),control);
        assertEquals(ErrorCode.CAPABILITY_UNSUPPORTED,result.errorCode()); verifyNoInteractions(client);
    }
}
