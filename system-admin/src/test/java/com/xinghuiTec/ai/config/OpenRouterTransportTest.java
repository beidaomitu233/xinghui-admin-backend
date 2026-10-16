package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenRouterTransportTest {
    HttpClient client; OpenRouterTransport transport; StringBuilder output;
    final InvocationControl control=new InvocationControl() {
        public Instant deadline() { return Instant.now().plusSeconds(5); } public boolean cancelled() { return false; }
    };
    @BeforeEach void setup() {
        client=mock(HttpClient.class); transport=new OpenRouterTransport(new ObjectMapper(),new AiModelConfigRules(),client); output=new StringBuilder();
    }
    void response(int status,String body) {
        HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
    }
    ExecutionOutcome call(boolean streaming) {
        return transport.call("unit/model",5,"controlled-secret",List.of(new AiMessage(MessageRole.USER,"short neutral test")),new Requirements(OutputFormat.TEXT,streaming,false),128,new StreamObserver() {
            public void onDelta(String text) { output.append(text); } public void onUsage(Usage usage) {}
        },control);
    }
    String frame(String content,String finish) {
        return "data: {\"model\":\"unit/model\",\"choices\":[{\"delta\":{\"content\":\""+content+"\"},\"finish_reason\":"+(finish==null?"null":"\""+finish+"\"")+"}]}\n\n";
    }
    @Test void streamingCommentsUsageAndSplitSecretRequireExplicitDoneAndStop() {
        response(200,": keepalive\n\n"+frame("controlled-",null)+frame("secret summary","stop")+
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}\n\ndata: [DONE]\n\n");
        var result=call(true);
        assertEquals(ExecutionStatus.SUCCEEDED,result.status()); assertEquals("[redacted] summary",output.toString()); assertEquals(5L,result.usage().totalTokens());
        verify(client,times(1)).sendAsync(argThat(r->r.uri().toString().equals(OpenRouterTransport.ENDPOINT)),any(HttpResponse.BodyHandler.class));
    }
    @Test void eofAndMidstreamErrorKeepPartialOutputWithoutRetryOrSuccess() {
        response(200,frame("partial summary","stop"));
        var eof=call(true); assertEquals(ExecutionStatus.INCOMPLETE,eof.status()); assertFalse(eof.retryable());
        output.setLength(0);
        response(200,frame("partial",null)+"data: {\"error\":{\"code\":502,\"message\":\"controlled-secret\"}}\n\n");
        var error=call(true); assertEquals(ExecutionStatus.INCOMPLETE,error.status()); assertFalse(error.retryable()); assertEquals("partial",output.toString());
    }
    @Test void beforeOutputErrorsAreClassifiedWithoutProviderBodyAndNoImplicitRetry() {
        response(429,"controlled-secret"); var rate=call(false);
        assertEquals(ErrorCode.RATE_LIMITED,rate.errorCode()); assertTrue(rate.retryable()); assertNull(rate.usage().totalTokens()); assertEquals("",output.toString());
        verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        response(400,"private invalid body"); assertFalse(call(false).retryable());
    }
    @Test void wrongModelAndOversizedPayloadFailBeforeLeakingContent() {
        response(200,"{\"model\":\"other/model\",\"choices\":[{\"message\":{\"content\":\"private\"},\"finish_reason\":\"stop\"}]}");
        assertEquals(ErrorCode.INVALID_RESPONSE,call(false).errorCode()); assertEquals("",output.toString());
        response(200,"x".repeat(1_048_577)); assertEquals(ErrorCode.OUTPUT_LIMIT,call(false).errorCode());
    }
    @Test void failedCancellationStoreClosesBlockedInputInsteadOfLosingDeadlineWatcher() {
        var unavailable=new java.util.concurrent.atomic.AtomicBoolean();
        var closed=new java.util.concurrent.CountDownLatch(1);
        InputStream blocked=new InputStream() {
            public int read() throws IOException {
                unavailable.set(true);
                try { if(!closed.await(2,java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("bounded fixture"); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("interrupted fixture"); }
                throw new IOException("closed fixture");
            }
            public void close() { closed.countDown(); }
        };
        HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(blocked);
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
        var result=transport.call("unit/model",3,"controlled-secret",List.of(new AiMessage(MessageRole.USER,"test")),new Requirements(OutputFormat.TEXT,true,false),128,mock(StreamObserver.class),new InvocationControl() {
            public Instant deadline() { return Instant.now().plusSeconds(3); }
            public boolean cancelled() { if(unavailable.get()) throw new IllegalStateException("private store failure"); return false; }
        });
        assertEquals(0,closed.getCount()); assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,result.errorCode()); assertFalse(result.retryable());
    }
}
