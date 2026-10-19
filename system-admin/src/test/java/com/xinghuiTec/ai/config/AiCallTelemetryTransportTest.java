package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import org.junit.jupiter.api.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real transport/parser with controlled HTTP receive boundaries. No vendor or persistence claims. */
class AiCallTelemetryTransportTest {
    private static final String KEY="sk-telemetry-private-0123456789";
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private HttpClient client;
    private OpenRouterTransport transport;
    private StringBuilder output;
    @BeforeEach void setup(){client=mock(HttpClient.class);transport=new OpenRouterTransport(JSON,new AiModelConfigRules(),client);output=new StringBuilder();}
    private InvocationControl control(){return new InvocationControl(){private final Instant deadline=Instant.now().plusSeconds(5);public Instant deadline(){return deadline;}public boolean cancelled(){return false;}};}
    private ExecutionOutcome call(boolean stream){return call(stream,control());}
    private ExecutionOutcome call(boolean stream,InvocationControl control){return transport.call("unit/model",5,KEY,List.of(new AiMessage(MessageRole.SYSTEM,"HIDDEN_OUTBOUND_PROMPT_SENTINEL"),new AiMessage(MessageRole.USER,"neutral fixture")),new Requirements(OutputFormat.TEXT,stream,false),128,new StreamObserver(){public void onDelta(String text){output.append(text);}public void onUsage(Usage value){}},control);}
    private void response(int status,String body){response(status,new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),Map.of("content-type",List.of("application/json")),0);}
    private void response(int status,InputStream body,Map<String,List<String>> headers,int delay){
        HttpResponse<InputStream> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);when(response.headers()).thenReturn(HttpHeaders.of(headers,(a,b)->true));
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{if(delay>0)Thread.sleep(delay);return CompletableFuture.completedFuture(response);});
    }
    private String event(String data){return "data: "+data+"\n\n";}
    private String frame(Map<String,Object> delta,String finish)throws Exception{return event(JSON.writeValueAsString(Map.of("model","unit/model","choices",List.of(new LinkedHashMap<String,Object>(){{put("delta",delta);put("finish_reason",finish);}}))));}
    private String okJson(String usage,String extra){return "{\"id\":\"reply-1\",\"model\":\"unit/model\",\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]"+(usage==null?"":",\"usage\":"+usage)+(extra==null?"":","+extra)+"}";}
    private String usage(){return "{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":1,\"audio_tokens\":7},\"completion_tokens_details\":{\"reasoning_tokens\":2},\"cost\":0.00000000000000012345,\"currency\":\"USD\",\"unknown_meter\":{\"new_field\":42}}";}
    private void oneCall(){verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));}
    private void bounded(AiCallTelemetry t){long bytes=0;for(String text:new String[]{t.responseHeadersJson(),t.usageJson(),t.responseBody()})if(text!=null)bytes+=text.getBytes(StandardCharsets.UTF_8).length;assertEquals(bytes,t.archivedBytes());assertTrue(bytes<=AiCallTelemetry.MAX_ARCHIVE_BYTES);}

    @Test void streamSeparatesHeadersBytesReasoningTextAndTailUsageWithoutDoubleCounting()throws Exception{
        String role=": heartbeat\n\nevent: update\nid: upstream-1\nretry: 200\n"+frame(Map.of("role","assistant","content",""),null);
        String reasoning=frame(Map.of("reasoning_content","thinking"),null),text=frame(Map.of("content","Hello"),null),stop=frame(Map.of("content"," world"),"stop");
        String tail=event("{\"choices\":[],\"usage\":"+usage()+",\"provider_extension\":{\"future_field\":\"retained\"}}");
        var chunks=List.of(role,reasoning,text,stop,tail,tail,"data: [DONE]\n\n");String received=String.join("",chunks);
        response(200,new Chunks(chunks,18),Map.of("content-type",List.of("text/event-stream"),"set-cookie",List.of("private-cookie-value")),12);
        var outcome=call(true);var t=outcome.telemetry();assertEquals(ExecutionStatus.SUCCEEDED,outcome.status());assertEquals("Hello world",output.toString());
        assertEquals("REASONING",t.firstOutputKind());assertTrue(t.firstByteMs()>t.responseHeadersMs());assertTrue(t.firstOutputMs()>t.firstByteMs());assertTrue(t.firstTextMs()>t.firstOutputMs());assertTrue(t.lastOutputMs()>=t.firstTextMs());
        assertEquals(2L,t.inputTokens());assertEquals(3L,t.outputTokens());assertEquals(5L,t.totalTokens());assertEquals(0L,t.cacheReadTokens());assertEquals(1L,t.cacheWriteTokens());assertEquals(2L,t.reasoningTokens());assertEquals(new BigDecimal("0.00000000000000012345"),t.reportedCost());assertEquals("USD",t.currency());
        assertTrue(t.usageJson().contains("unknown_meter"));assertTrue(t.responseBody().contains("future_field"));assertTrue(t.responseBody().contains("retry"));assertFalse(t.responseHeadersJson().contains("private-cookie-value"));assertTrue(t.redacted());assertEquals("COMPLETE",t.captureStatus());assertEquals("SSE",t.responseFormat());assertEquals(received.getBytes(StandardCharsets.UTF_8).length,t.receivedBytes());
        var events=JSON.readTree(t.responseBody());long previous=-1;for(int i=0;i<events.size();i++){assertEquals(i+1,events.get(i).path("sequence").asInt());assertTrue(events.get(i).path("receivedMs").asLong()>=previous);previous=events.get(i).path("receivedMs").asLong();}
        assertFalse(t.responseBody().contains("HIDDEN_OUTBOUND_PROMPT_SENTINEL"));bounded(t);oneCall();
    }
    @Test void nonstreamKeepsUnknownJsonAndExplicitZeroWithoutInventingOutputTiming(){
        String body=okJson("{\"prompt_tokens\":0,\"completion_tokens\":1,\"extended\":{\"images\":2}}","\"new_provider_field\":[1,2,3]");response(200,body);
        var t=call(false).telemetry();assertEquals("COMPLETE",t.captureStatus());assertEquals(0L,t.inputTokens());assertNull(t.totalTokens());assertNull(t.reportedCost());assertNull(t.currency());assertNull(t.firstOutputMs());assertNull(t.firstTextMs());assertNull(t.lastOutputMs());assertNull(t.firstOutputKind());assertNotNull(t.firstByteMs());assertTrue(t.responseBody().contains("new_provider_field"));assertEquals("unit/model",t.returnedModel());assertEquals("stop",t.finishReason());assertEquals(body.getBytes(StandardCharsets.UTF_8).length,t.receivedBytes());bounded(t);oneCall();
    }
    @Test void httpErrorArchivesSafeDetailsHeadersAndExactBytesWithoutChangingRetryClassification(){
        String body="{\"error\":{\"code\":401,\"message\":\"bad "+KEY+"\",\"access_token\":\"other-private-token\",\"extra\":{\"field\":7}},\"credential\":\"other-private-credential\"}";
        response(401,new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),Map.of("set-cookie",List.of("session=private"),"authorization",List.of("Bearer private"),"x-diagnostic",List.of(KEY),"content-type",List.of("application/json")),0);
        var result=call(false);var t=result.telemetry();assertEquals(ErrorCode.AUTH_FAILED,result.errorCode());assertTrue(result.retryable());assertFalse(result.outputStarted());assertEquals(401,t.httpStatus());assertEquals("COMPLETE",t.captureStatus());assertTrue(t.responseBody().contains("extra"));
        for(String secret:List.of(KEY,"other-private-token","other-private-credential","session=private","Bearer private"))assertFalse((t.responseBody()+t.responseHeadersJson()+result.toString()+t.toString()).contains(secret));
        assertEquals(body.getBytes(StandardCharsets.UTF_8).length,t.receivedBytes());bounded(t);oneCall();
    }
    @Test void malformedJsonAndEscapedCredentialNamesFailClosedWithoutLeakingPartialSecrets(){
        String malformed="{\"extension\":7,\"message\":\""+KEY+"\",\"\\u0061pi_key\":\"other-private-incomplete";response(200,malformed);
        var result=call(false);var t=result.telemetry();assertFalse(result.retryable());assertEquals(ExecutionStatus.INCOMPLETE,result.status());assertEquals("PARTIAL",t.captureStatus());assertEquals("TEXT",t.responseFormat());assertTrue(t.responseBody().contains("extension"));assertFalse(t.responseBody().contains(KEY));assertFalse(t.responseBody().contains("other-private"));assertTrue(t.redacted());bounded(t);oneCall();
    }
    @Test void crossFrameAndSingleByteKeyEchoIsScrubbedBeforeOrderedSseArchiving()throws Exception{
        String first=KEY.substring(0,13),last=KEY.substring(13);String body=frame(Map.of("content",first),null)+frame(Map.of("content",last+" safe"),"stop")+"data: [DONE]\n\n";
        response(200,new Chunks(body.chars().mapToObj(c->Character.toString((char)c)).toList(),0),Map.of("content-type",List.of("text/event-stream")),0);
        var result=call(true);var t=result.telemetry();assertEquals(ExecutionStatus.SUCCEEDED,result.status());assertFalse(t.responseBody().contains(first));assertFalse(t.responseBody().contains(last));assertFalse(t.responseBody().contains(KEY));assertTrue(t.redacted());assertEquals("[redacted] safe",output.toString());bounded(t);oneCall();
        response(200,frame(Map.of("reasoning_content",first),null)+frame(Map.of("content",last+" safe"),"stop")+"data: [DONE]\n\n");var crossKind=call(true).telemetry();assertFalse(crossKind.responseBody().contains(first));assertFalse(crossKind.responseBody().contains(last));bounded(crossKind);
    }
    @Test void recursiveCredentialsEncodedKeyFormsAndStringifiedJsonAreRedacted()throws Exception{
        String base64=Base64.getEncoder().encodeToString(KEY.getBytes(StandardCharsets.UTF_8));String encoded=java.net.URLEncoder.encode(KEY+" ",StandardCharsets.UTF_8).stripTrailing();
        String extra="\"nested\":"+JSON.writeValueAsString(Map.of("api_key","another-private-key","safe","preserved","echo",base64,"embedded",JSON.writeValueAsString(Map.of("password","private-password","unknown",9))))+",\"encoded\":"+JSON.writeValueAsString(encoded);
        response(200,okJson(null,extra));var t=call(false).telemetry();assertFalse(t.responseBody().contains(KEY));assertFalse(t.responseBody().contains(base64));assertFalse(t.responseBody().contains("another-private-key"));assertFalse(t.responseBody().contains("private-password"));assertTrue(t.responseBody().contains("preserved"));assertTrue(t.responseBody().contains("unknown"));bounded(t);
    }
    @Test void boundedArchiveTruncatesAfterScrubbingAndLeavesActualExecutionSuccessful(){
        String huge="汉".repeat(100000)+KEY;String body=okJson(usage(),"\"huge_extension\":\""+huge+"\"");response(200,body);
        var result=call(false);var t=result.telemetry();assertEquals(ExecutionStatus.SUCCEEDED,result.status());assertTrue(t.truncated());assertEquals("PARTIAL",t.captureStatus());assertEquals("CAPTURE_LIMIT",t.captureReason());assertFalse(Objects.toString(t.responseBody(),"").contains(KEY));assertNotNull(t.responseBody());assertDoesNotThrow(()->JSON.readTree(t.responseBody()));assertEquals(new BigDecimal("0.00000000000000012345"),t.reportedCost());bounded(t);oneCall();
    }
    @Test void interruptedStreamDoesNotArchiveAnIncompleteCredentialPrefix()throws Exception{
        String prefix=KEY.substring(0,15);response(200,frame(Map.of("content",prefix),null)+"data: {\"choices\":[{\"delta\":{\"content\":\""+prefix);
        var result=call(true);var t=result.telemetry();assertEquals(ExecutionStatus.INCOMPLETE,result.status());assertFalse(result.retryable());assertEquals("PARTIAL",t.captureStatus());assertFalse(t.responseBody().contains(prefix));assertTrue(t.redacted());bounded(t);oneCall();
    }
    @Test void cancellationBeforeHeadersRetainsSendTimingWithoutPretendingAResponseArrived(){
        var future=new CompletableFuture<HttpResponse<InputStream>>();when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(future);
        long beginning=System.nanoTime();var result=call(true,new InvocationControl(){private final Instant end=Instant.now().plusSeconds(3);public Instant deadline(){return end;}public boolean cancelled(){return System.nanoTime()-beginning>80_000_000;}});
        var t=result.telemetry();assertEquals(ExecutionStatus.CANCELLED,result.status());assertTrue(future.isCancelled());assertFalse(result.retryable());assertNotNull(t.startedAt());assertNotNull(t.elapsedMs());assertNull(t.httpStatus());assertNull(t.firstByteMs());assertNull(t.responseHeadersMs());assertNull(t.firstOutputMs());assertEquals("NOT_CAPTURED",t.captureStatus());assertEquals(0L,t.receivedBytes());oneCall();
    }
    @Test void cancellationAndDeadlineAfterOutputKeepPartialTelemetryAndCloseRealBody()throws Exception{
        for(boolean cancel:List.of(true,false)){
            setup();var close=new CountDownLatch(1);var blocked=new AtomicBoolean();byte[] first=frame(Map.of("content","partial text"),null).getBytes(StandardCharsets.UTF_8);
            InputStream stream=new InputStream(){int offset;public int read()throws IOException{var one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}public int read(byte[] b,int o,int l)throws IOException{if(offset<first.length){int n=Math.min(l,first.length-offset);System.arraycopy(first,offset,b,o,n);offset+=n;return n;}blocked.set(true);try{if(!close.await(2,TimeUnit.SECONDS))throw new IOException("bounded fixture");}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new IOException("closed fixture");}public void close(){close.countDown();}};
            response(200,stream,Map.of("content-type",List.of("text/event-stream")),0);Instant deadline=Instant.now().plusMillis(cancel?2000:120);
            var result=call(true,new InvocationControl(){public Instant deadline(){return deadline;}public boolean cancelled(){return cancel&&blocked.get();}});var t=result.telemetry();
            assertEquals(cancel?ExecutionStatus.CANCELLED:ExecutionStatus.TIMED_OUT,result.status());assertFalse(result.retryable());assertEquals(0,close.getCount());assertNotNull(t.firstOutputMs());assertNotNull(t.firstTextMs());assertEquals("PARTIAL",t.captureStatus());assertTrue(t.responseBody().contains("partial text"));bounded(t);oneCall();
        }
    }
    @Test void noUpstreamAdmissionHasUnknownMeasurementsAndCompatibleOutcomeRemainsRedacted(){
        var result=call(true,new InvocationControl(){public Instant deadline(){return Instant.now().plusSeconds(2);}public boolean cancelled(){return true;}});var t=result.telemetry();assertNull(t.startedAt());assertNull(t.elapsedMs());assertNull(t.receivedBytes());assertEquals("NOT_CAPTURED",t.captureStatus());verifyNoInteractions(client);
        var old=new ExecutionOutcome(ExecutionStatus.SUCCEEDED,null,false,true,new Usage(0L,0L,0L),"provider-id",0);assertNull(old.telemetry());assertEquals("ExecutionOutcome[REDACTED]",old.toString());assertEquals("AiCallTelemetry[REDACTED]",t.toString());
    }
    @Test void extremeScientificCostsStayInRawUsageButNeverExpandNormalizedMoney(){
        for(String cost:List.of("1e1000000","1e-1000000","\"1e2147483647\"")){
            response(200,okJson("{\"cost\":"+cost+",\"currency\":\"EUR\"}",null));var result=call(false);var t=result.telemetry();assertEquals(ExecutionStatus.SUCCEEDED,result.status());assertNull(t.reportedCost());assertEquals("UNSUPPORTED_COST",t.captureReason());assertTrue(t.usageJson().contains("cost"));bounded(t);
        }
        response(200,okJson("{\"cost\":-0.00123,\"currency\":\"EUR\"}",null));var t=call(false).telemetry();assertEquals(new BigDecimal("-0.00123"),t.reportedCost());assertEquals("EUR",t.currency());
    }
    @Test void archiveFailureIsSafeAndCannotTriggerAnotherModelCall(){
        HttpResponse<InputStream> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(new ByteArrayInputStream(okJson(null,null).getBytes(StandardCharsets.UTF_8)));when(response.headers()).thenReturn(null);
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
        var result=call(false);assertEquals(ExecutionStatus.SUCCEEDED,result.status());assertEquals("FAILED",result.telemetry().captureStatus());assertEquals("ARCHIVE_FAILED",result.telemetry().captureReason());assertEquals("OK",output.toString());oneCall();
    }
    @Test void openaiOptsIntoUsageOnlyForStreamingWithoutChangingTokenBudgetOrRouting()throws Exception{
        for(boolean stream:List.of(true,false)){
            response(200,stream?frame(Map.of("content","OK"),"stop")+"data: [DONE]\n\n":okJson(null,null));
            assertEquals(ExecutionStatus.SUCCEEDED,transport.callForProvider("openai",AiProviderPresets.require("openai").chatEndpoint(),"unit/model",5,KEY,List.of(new AiMessage(MessageRole.USER,"fixture")),new Requirements(OutputFormat.TEXT,stream,false),128,mock(StreamObserver.class),control()).status());
        }
        var requests=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(2)).sendAsync(requests.capture(),any(HttpResponse.BodyHandler.class));
        for(var request:requests.getAllValues()){
            var bytes=new ByteArrayOutputStream();var done=new CompletableFuture<Void>();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<java.nio.ByteBuffer>(){public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer value){byte[] b=new byte[value.remaining()];value.get(b);bytes.writeBytes(b);}public void onError(Throwable e){done.completeExceptionally(e);}public void onComplete(){done.complete(null);}});done.get(1,TimeUnit.SECONDS);
            var body=JSON.readTree(bytes.toByteArray());assertEquals(body.path("stream").asBoolean(),body.path("stream_options").path("include_usage").asBoolean());assertEquals(128,body.path("max_completion_tokens").asInt());assertFalse(body.has("max_tokens"));assertFalse(body.has("provider"));
        }
    }
    @Test void returnedModelFinishReasonAndProviderIdentifiersCannotEchoCredentialScalars()throws Exception{
        for(String echo:List.of(KEY,Base64.getUrlEncoder().withoutPadding().encodeToString(KEY.getBytes(StandardCharsets.UTF_8)))){
            var body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(okJson(null,null));body.put("id",echo);response(200,body.toString());var id=call(false);assertEquals(ExecutionStatus.SUCCEEDED,id.status());assertNull(id.providerRequestId());assertFalse(JSON.writeValueAsString(id.telemetry()).contains(echo));
            body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(okJson(null,null));body.put("model",echo);response(200,body.toString());var model=call(false);assertNull(model.telemetry().returnedModel());assertFalse(JSON.writeValueAsString(model.telemetry()).contains(echo));
            body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(okJson(null,null));((com.fasterxml.jackson.databind.node.ObjectNode)body.path("choices").get(0)).put("finish_reason",echo);response(200,body.toString());var finish=call(false);assertNull(finish.telemetry().finishReason());assertFalse(JSON.writeValueAsString(finish.telemetry()).contains(echo));
        }
    }
    private static final class Chunks extends InputStream{
        final List<byte[]> chunks;final int delay;int index,offset;
        Chunks(List<String> chunks,int delay){this.chunks=chunks.stream().map(s->s.getBytes(StandardCharsets.UTF_8)).toList();this.delay=delay;}
        @Override public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        @Override public int read(byte[] b,int o,int l)throws IOException{if(index>=chunks.size())return -1;if(offset==0&&delay>0)try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted fixture");}byte[] next=chunks.get(index);int n=Math.min(l,next.length-offset);System.arraycopy(next,offset,b,o,n);offset+=n;if(offset==next.length){index++;offset=0;}return n;}
    }
}
