package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual segmented stream reads under a controlled HttpClient; no real provider calls. */
class OpenRouterRetryBoundaryTest {
    final ObjectMapper json=new ObjectMapper();
    HttpClient client; OpenRouterTransport transport;
    StringBuilder delivered; AtomicInteger deltas;
    @BeforeEach void setup() {
        client=mock(HttpClient.class); transport=new OpenRouterTransport(json,new AiModelConfigRules(),client);
        delivered=new StringBuilder(); deltas=new AtomicInteger();
    }
    void response(InputStream stream) {
        HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(stream);
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
    }
    String contentFrame(OutputFormat format) throws Exception {
        String content=format==OutputFormat.JSON_OBJECT?"{":"x";
        return "data: "+json.writeValueAsString(Map.of("model","openai/gpt-4o-mini","choices",List.of(Map.of("delta",Map.of("content",content)))))+"\n\n";
    }
    ExecutionOutcome call(OutputFormat format) {
        return call(format,true);
    }
    ExecutionOutcome call(OutputFormat format,boolean streaming) {
        return transport.call("openai/gpt-4o-mini",5,"controlled-secret",List.of(new AiMessage(MessageRole.USER,"test")),new Requirements(format,streaming,false),128,new StreamObserver() {
            public void onDelta(String text) { deltas.incrementAndGet(); delivered.append(text); }
            public void onUsage(Usage usage) {}
        },new InvocationControl() {
            public Instant deadline() { return Instant.now().plusSeconds(5); }
            public boolean cancelled() { return false; }
        });
    }
    void assertNonRetryablePartial(OutputFormat format,ExecutionOutcome outcome,ErrorCode code) {
        assertEquals(ExecutionStatus.INCOMPLETE,outcome.status());
        assertEquals(code,outcome.errorCode()); assertTrue(outcome.outputStarted()); assertFalse(outcome.retryable());
        if(format==OutputFormat.JSON_OBJECT) { assertEquals("",delivered.toString()); assertEquals(0,deltas.get()); }
        else assertEquals("x",delivered.toString());
        verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @ParameterizedTest @EnumSource(OutputFormat.class)
    void contentInFirstReadThenIOExceptionNeverRetriesEvenBeforeObserverDelivery(OutputFormat format) throws Exception {
        byte[] first=contentFrame(format).getBytes(StandardCharsets.UTF_8);
        var reads=new AtomicInteger(); var deliveredBeforeFault=new AtomicInteger(-1);
        response(new InputStream() {
            int position;
            public int read(byte[] target,int offset,int length) throws IOException {
                reads.incrementAndGet();
                if(position==first.length) { deliveredBeforeFault.set(deltas.get()); throw new IOException("controlled second-read failure"); }
                int count=Math.min(length,first.length-position);
                System.arraycopy(first,position,target,offset,count); position+=count; return count;
            }
            public int read() throws IOException { byte[] one=new byte[1]; read(one,0,1); return one[0]&255; }
        });
        var outcome=call(format);
        assertTrue(reads.get()>=2); assertEquals(0,deliveredBeforeFault.get());
        assertNonRetryablePartial(format,outcome,ErrorCode.NETWORK_ERROR);
    }
    @ParameterizedTest @EnumSource(OutputFormat.class)
    void contentFrameThenProvider503NeverRetriesOrDeliversAnInvalidJsonObject(OutputFormat format) throws Exception {
        var stream=new SequenceInputStream(new ByteArrayInputStream(contentFrame(format).getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream("data: {\"error\":{\"code\":503}}\n\n".getBytes(StandardCharsets.UTF_8)));
        response(stream);
        assertNonRetryablePartial(format,call(format),ErrorCode.PROVIDER_UNAVAILABLE);
    }
    @ParameterizedTest @EnumSource(OutputFormat.class)
    void provider503BeforeAnyContentStillAllowsTheCallerToApplyItsRetryPolicy(OutputFormat format) {
        for(boolean streaming:List.of(true,false)) {
            String body=streaming?"data: {\"error\":{\"code\":503}}\n\n":"{\"error\":{\"code\":503}}";
            response(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            var result=call(format,streaming);
            assertEquals(ExecutionStatus.FAILED,result.status()); assertEquals(ErrorCode.PROVIDER_UNAVAILABLE,result.errorCode());
            assertFalse(result.outputStarted()); assertTrue(result.retryable()); assertEquals("",delivered.toString());
        }
    }
    @ParameterizedTest @EnumSource(OutputFormat.class)
    void partialNonStreamingBodyThenIOExceptionIsNotReplayed(OutputFormat format) throws Exception {
        byte[] prefix=json.writeValueAsBytes(Map.of("model","openai/gpt-4o-mini","choices",List.of(Map.of("message",Map.of("content",format==OutputFormat.JSON_OBJECT?"{}":"x"),"finish_reason","stop"))));
        var faults=new AtomicInteger();
        response(new InputStream() {
            int position;
            public int read(byte[] target,int offset,int length) throws IOException {
                if(position==prefix.length) { faults.incrementAndGet(); assertEquals(0,deltas.get()); throw new IOException("controlled second-read failure"); }
                int count=Math.min(length,prefix.length-position); System.arraycopy(prefix,position,target,offset,count); position+=count; return count;
            }
            public int read() throws IOException { byte[] one=new byte[1]; read(one,0,1); return one[0]&255; }
        });
        var result=call(format,false);
        assertEquals(1,faults.get()); assertEquals(ExecutionStatus.INCOMPLETE,result.status());
        assertEquals(ErrorCode.NETWORK_ERROR,result.errorCode()); assertTrue(result.outputStarted()); assertFalse(result.retryable());
        assertEquals("",delivered.toString());
        verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
}
