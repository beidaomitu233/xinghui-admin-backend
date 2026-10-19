package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLHandshakeException;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled exceptional futures and receive bodies. No outbound network and no retry emulation. */
class AiCallTelemetryFailureReasonTest {
    private static final String KEY="sk-private-failure-fixture-0123456789";
    private static final String PRIVATE="https://private-host.invalid/path?key="+KEY;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private HttpClient client;
    private OpenRouterTransport transport;
    @BeforeEach void setup(){client=mock(HttpClient.class);transport=new OpenRouterTransport(json,new AiModelConfigRules(),client);}
    private InvocationControl control(){return new InvocationControl(){private final Instant end=Instant.now().plusSeconds(3);public Instant deadline(){return end;}public boolean cancelled(){return false;}};}
    private ExecutionOutcome call(boolean stream,InvocationControl control){return transport.call("unit/model",3,KEY,List.of(new AiMessage(MessageRole.USER,"fixture")),new Requirements(OutputFormat.TEXT,stream,false),128,mock(StreamObserver.class),control);}
    private void failed(Throwable cause){when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.failedFuture(cause));}
    private void single(){verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));}
    private void scrubbed(AiCallTelemetry telemetry)throws Exception{String archive=json.writeValueAsString(telemetry);assertFalse(archive.contains(KEY));assertFalse(archive.contains("private-host"));assertFalse(archive.contains("Exception"));assertEquals("AiCallTelemetry[REDACTED]",telemetry.toString());}
    @Test void wrappedFutureFailuresExposeOnlyFixedReasonsAndKeepExistingNetworkRetryOutcome()throws Exception{
        var causes=List.of(new HttpTimeoutException(PRIVATE),new HttpConnectTimeoutException(PRIVATE),new SSLHandshakeException(PRIVATE),new ConnectException(PRIVATE),new UnknownHostException(PRIVATE),new IOException(PRIVATE),new IllegalStateException(PRIVATE));
        var expected=List.of("HTTP_TIMEOUT","CONNECT_TIMEOUT","TLS_ERROR","CONNECT_ERROR","DNS_ERROR","IO_ERROR","OTHER_ERROR");
        for(int i=0;i<causes.size();i++){
            reset(client);failed(new CompletionException(new ExecutionException(causes.get(i))));var outcome=call(true,control());var t=outcome.telemetry();
            assertEquals(ExecutionStatus.FAILED,outcome.status());assertEquals(ErrorCode.NETWORK_ERROR,outcome.errorCode());assertTrue(outcome.retryable());assertFalse(outcome.outputStarted());
            assertEquals("NOT_CAPTURED",t.captureStatus());assertEquals("NO_RESPONSE_"+expected.get(i),t.captureReason());assertNull(t.httpStatus());assertNull(t.firstByteMs());assertNull(t.responseBody());scrubbed(t);single();
        }
    }
    @Test void cancelledFutureAndPreSendCancellationRetainTheirExistingBusinessSemantics()throws Exception{
        var cancelled=new AtomicBoolean();when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{cancelled.set(true);return CompletableFuture.failedFuture(new CancellationException(PRIVATE));});
        var outcome=call(true,new InvocationControl(){private final Instant end=Instant.now().plusSeconds(3);public Instant deadline(){return end;}public boolean cancelled(){return cancelled.get();}});
        assertEquals(ExecutionStatus.CANCELLED,outcome.status());assertEquals(ErrorCode.CANCELLED,outcome.errorCode());assertFalse(outcome.retryable());assertEquals("NO_RESPONSE_CANCELLED",outcome.telemetry().captureReason());scrubbed(outcome.telemetry());single();
        clearInvocations(client);var before=call(true,new InvocationControl(){public Instant deadline(){return Instant.now().plusSeconds(3);}public boolean cancelled(){return true;}});
        assertEquals("NO_UPSTREAM_CANCELLED",before.telemetry().captureReason());assertNull(before.telemetry().startedAt());assertEquals(ErrorCode.CANCELLED,before.errorCode());assertFalse(before.retryable());verifyNoInteractions(client);scrubbed(before.telemetry());
    }
    @Test void causeCyclesAndExcessDepthAreBoundedWithoutPersistingUnknownMessages()throws Exception{
        var first=new IllegalStateException(PRIVATE);var second=new IllegalArgumentException(PRIVATE);first.initCause(second);second.initCause(first);failed(first);
        assertEquals("NO_RESPONSE_OTHER_ERROR",call(false,control()).telemetry().captureReason());single();
        reset(client);Throwable deep=new SSLHandshakeException(PRIVATE);for(int i=0;i<10;i++)deep=new IllegalStateException(PRIVATE,deep);failed(deep);var result=call(false,control());
        assertEquals("NO_RESPONSE_OTHER_ERROR",result.telemetry().captureReason());assertEquals(ErrorCode.NETWORK_ERROR,result.errorCode());assertTrue(result.retryable());scrubbed(result.telemetry());single();
    }
    @Test void partialTruncatedAndHttpResponseReasonsAreNotOverwrittenByCauseClassification()throws Exception{
        byte[] frame="data: {\"model\":\"unit/model\",\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8);
        InputStream broken=new InputStream(){int offset;public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}public int read(byte[] b,int o,int l)throws IOException{if(offset>=frame.length)throw new ConnectException(PRIVATE);int n=Math.min(l,frame.length-offset);System.arraycopy(frame,offset,b,o,n);offset+=n;return n;}};
        response(200,broken);var partial=call(true,control());assertEquals(ExecutionStatus.INCOMPLETE,partial.status());assertFalse(partial.retryable());assertEquals("TRANSPORT_PARTIAL",partial.telemetry().captureReason());assertEquals(200,partial.telemetry().httpStatus());scrubbed(partial.telemetry());single();
        reset(client);response(200,new ByteArrayInputStream("x".repeat(400000).getBytes(StandardCharsets.UTF_8)));var truncated=call(false,control());assertEquals("CAPTURE_LIMIT",truncated.telemetry().captureReason());assertTrue(truncated.telemetry().truncated());single();
        reset(client);response(500,new ByteArrayInputStream(new byte[0]));var http=call(false,control());assertEquals(ErrorCode.PROVIDER_UNAVAILABLE,http.errorCode());assertTrue(http.retryable());assertEquals(500,http.telemetry().httpStatus());assertNull(http.telemetry().captureReason());assertEquals("COMPLETE",http.telemetry().captureStatus());single();
    }
    private void response(int status,InputStream body){HttpResponse<InputStream> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);when(response.headers()).thenReturn(HttpHeaders.of(Map.of("content-type",List.of("application/json")),(a,b)->true));when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));}
}
