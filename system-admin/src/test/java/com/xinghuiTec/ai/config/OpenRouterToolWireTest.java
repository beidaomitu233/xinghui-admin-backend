package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import org.junit.jupiter.api.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled protocol evidence. These fixtures never contact a provider or perform business mutations. */
class OpenRouterToolWireTest {
    final ObjectMapper json=new ObjectMapper();
    OpenRouterTransportTest fixture;List<String> delivered;
    final InvocationControl control=new InvocationControl(){public Instant deadline(){return Instant.now().plusSeconds(10);}public boolean cancelled(){return false;}};
    final String request="""
            {"model":"untrusted/model","messages":[{"role":"user","content":"Controlled tool fixture"}],
             "tools":[{"type":"function","function":{"name":"records_mutate","parameters":{"type":"object"}}}],
             "tool_choice":"auto","parallel_tool_calls":true,"max_tokens":999999,"user":"ignored"}
            """;
    @BeforeEach void setup(){fixture=new OpenRouterTransportTest();fixture.setup();delivered=new ArrayList<>();}
    ExecutionOutcome call(){return fixture.transport.callTools("openrouter",OpenRouterTransport.ENDPOINT,"unit/model",10,"controlled-secret",request,128,delivered::add,control);}
    @Test void imageInputLargerThanTheOldTextLimitReachesTheConfiguredTransport()throws Exception {
        String image="data:image/png;base64,"+"A".repeat(400_000);
        String wire=json.writeValueAsString(Map.of("messages",List.of(Map.of("role","user","content",List.of(
                Map.of("type","text","text","照图新增商品"),Map.of("type","image_url","image_url",Map.of("url",image)))))));
        fixture.response(200,frame(Map.of("content","已识别图片"),null)+end("stop"));
        var result=fixture.transport.callTools("openrouter",OpenRouterTransport.ENDPOINT,"unit/model",10,"controlled-secret",wire,128,delivered::add,control);
        assertEquals(ExecutionStatus.SUCCEEDED,result.status());
        var captured=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(fixture.client).sendAsync(captured.capture(),any(HttpResponse.BodyHandler.class));
        var sent=json.readTree(new OpenRouterJsonObjectTest().requestBody(captured.getValue()));
        assertEquals(image,sent.path("messages").path(0).path("content").path(1).path("image_url").path("url").asText());
    }
    String frame(Map<String,Object> delta,String finish)throws Exception {
        var choice=new LinkedHashMap<String,Object>();choice.put("index",0);choice.put("delta",delta);choice.put("finish_reason",finish);
        return "data: "+json.writeValueAsString(Map.of("id","fixture-id","model","unit/model","choices",List.of(choice)))+"\n\n";
    }
    String first(String name,String args)throws Exception {
        return frame(Map.of("tool_calls",List.of(Map.of("index",0,"id","call_fixture","type","function","function",Map.of("name",name,"arguments",args)))),null);
    }
    String rest(String args)throws Exception {return frame(Map.of("tool_calls",List.of(Map.of("index",0,"function",Map.of("arguments",args)))),null);}
    String end(String finish)throws Exception {return frame(Map.of(),finish)+"data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":8,\"total_tokens\":12,\"cost\":0}}\n\ndata: [DONE]\n\n";}

    @Test void toolOnlyTurnPreservesExactArgumentsAndCapturesUsageAndFirstOutput()throws Exception {
        String arguments="{\"password\":\"legitimate-business-value\",\"count\":0,\"enabled\":false}";
        fixture.response(200,first("records_mutate",arguments.substring(0,12))+rest(arguments.substring(12))+end("tool_calls"));
        var result=call();assertEquals(ExecutionStatus.SUCCEEDED,result.status());assertTrue(result.outputStarted());assertFalse(result.retryable());
        assertEquals(12L,result.usage().totalTokens());assertEquals("TOOL_CALL",result.telemetry().firstOutputKind());assertNotNull(result.telemetry().firstOutputMs());assertNull(result.telemetry().firstTextMs());
        var rebuilt=new StringBuilder();for(String wire:delivered)for(var fragment:json.readTree(wire).path("choices").path(0).path("delta").path("tool_calls"))rebuilt.append(fragment.path("function").path("arguments").asText(""));
        assertEquals(arguments,rebuilt.toString());
        var captured=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(fixture.client).sendAsync(captured.capture(),any(HttpResponse.BodyHandler.class));
        var body=json.readTree(new OpenRouterJsonObjectTest().requestBody(captured.getValue()));
        assertEquals("unit/model",body.path("model").asText());assertEquals(128,body.path("max_tokens").asInt());
        assertFalse(body.has("parallel_tool_calls"));assertFalse(body.has("tool_choice"));assertTrue(body.path("provider").path("require_parameters").asBoolean());assertFalse(body.has("user"));
    }
    @Test void incompleteInvalidUnknownAndOversizedToolCallsNeverReachSdkOrRetry()throws Exception {
        var valid=first("records_mutate","{}");
        for(String response:List.of(valid+frame(Map.of(),"tool_calls"),first("records_mutate","{")+end("tool_calls"),
                first("unregistered_tool","{}")+end("tool_calls"),first("records_mutate","{\"a\":1,\"a\":2}")+end("tool_calls"),
                valid+end("stop"),valid+end("length"))) {
            fixture.response(200,response);delivered.clear();var result=call();
            assertNotEquals(ExecutionStatus.SUCCEEDED,result.status());assertTrue(result.outputStarted());assertFalse(result.retryable());assertTrue(delivered.isEmpty());
        }
        fixture.response(200,first("records_mutate","x".repeat(65536))+end("tool_calls"));assertEquals(ErrorCode.OUTPUT_LIMIT,call().errorCode());assertTrue(delivered.isEmpty());
    }
    @Test void ordinaryFinalTextAndUsageAreStillValidToolTurns()throws Exception {
        fixture.response(200,frame(Map.of("content","Task completed"),null)+end("stop"));
        var result=call();assertEquals(ExecutionStatus.SUCCEEDED,result.status());assertEquals("TEXT",result.telemetry().firstOutputKind());assertFalse(delivered.isEmpty());
    }
    @Test void repeatedTerminalMetadataOnUsageTrailerDoesNotRejectACompleteToolCall()throws Exception {
        String trailer=frame(Map.of("role","assistant","content",""),"tool_calls");
        String usage=trailer.replace("\"choices\":","\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":8,\"total_tokens\":12},\"choices\":");
        fixture.response(200,first("records_mutate","{\"count\":0}")+trailer+usage+"data: [DONE]\n\n");
        var result=call();assertEquals(ExecutionStatus.SUCCEEDED,result.status());assertEquals(12L,result.usage().totalTokens());
        long fragments=0;for(String value:delivered)fragments+=json.readTree(value).path("choices").path(0).path("delta").path("tool_calls").size();
        assertEquals(1,fragments);
    }
    @Test void actualOutputOrConflictingReasonAfterTerminalStillCannotReachTheSdk()throws Exception {
        var done=frame(Map.of("role","assistant","content",""),"tool_calls");
        for(String extra:List.of(frame(Map.of("content","late text"),"tool_calls"),frame(Map.of("reasoning","late reasoning"),"tool_calls"),frame(Map.of(),"stop"),rest("{}"))){
            delivered.clear();fixture.response(200,first("records_mutate","{}")+done+extra+"data: [DONE]\n\n");
            var result=call();assertEquals(ErrorCode.INVALID_RESPONSE,result.errorCode());assertTrue(result.outputStarted());assertFalse(result.retryable());assertTrue(delivered.isEmpty());
        }
    }
    @Test void longFinalTextStreamsBeforeAnEofFailureWithoutReplayingOrExposingTools()throws Exception {
        fixture.response(200,frame(Map.of("content","A sufficiently long ordinary assistant reply for streaming"),null));
        var result=call();assertEquals(ExecutionStatus.INCOMPLETE,result.status());assertFalse(result.retryable());
        assertTrue(delivered.stream().anyMatch(value->value.contains("sufficiently long")));
        assertTrue(delivered.stream().noneMatch(value->value.contains("tool_calls")));
    }
    @Test void splitCredentialEchoInTextOrToolArgumentsIsRejectedWithoutRewritingOperations()throws Exception {
        for(String body:List.of(frame(Map.of("content","controlled-"),null)+frame(Map.of("content","secret"),null)+end("stop"),
                first("records_mutate","{\"value\":\"controlled-")+rest("secret\"}")+end("tool_calls"),
                first("records_mutate","{\"value\":\"controlled-\\u0073ecret\"}")+end("tool_calls"))) {
            fixture.response(200,body);var result=call();assertEquals(ErrorCode.INVALID_RESPONSE,result.errorCode());assertFalse(result.retryable());assertTrue(delivered.isEmpty());
        }
    }
    @Test void beforeOutputUnavailableCanFallbackButAStartedToolTurnCannot()throws Exception {
        fixture.response(503,"fixture unavailable");assertTrue(call().retryable());
        fixture.response(200,first("records_mutate","{")+"data: {\"error\":{\"code\":503}}\n\n");
        var partial=call();assertEquals(ExecutionStatus.INCOMPLETE,partial.status());assertFalse(partial.retryable());assertTrue(delivered.isEmpty());
    }
    @Test void malformedFormatterInputAndOrphanToolResultsAreRejectedBeforeNetwork() {
        for(String invalid:List.of("{}",request.replace("\"role\":\"user\"","\"role\":\"tool\""),request.replace("\"parameters\":{\"type\":\"object\"}","\"parameters\":null"))) {
            assertEquals(ErrorCode.INVALID_INPUT,assertThrows(AiRuntimeException.class,()->fixture.transport.callTools("openrouter",OpenRouterTransport.ENDPOINT,"unit/model",10,"controlled-secret",invalid,128,delivered::add,control)).code());
        }
        verifyNoInteractions(fixture.client);
    }
    @Test void completedToolHistoryRemainsLinkedOnNextTurn() {
        String history=request.replace("{\"role\":\"user\",\"content\":\"Controlled tool fixture\"}","""
                {"role":"user","content":"Controlled tool fixture"},
                {"role":"assistant","content":null,"tool_calls":[{"id":"call_old","type":"function","function":{"name":"records_mutate","arguments":"{}"}}]},
                {"role":"tool","tool_call_id":"call_old","content":"ok"}
                """);
        assertDoesNotThrow(()->new OpenAiToolWire(history,delivered::add));
    }
    @Test void deactivatedSkillHistoryDoesNotRequireItsSchemaInTheCurrentTurn()throws Exception {
        String wire="""
            {"messages":[{"role":"user","content":"查看商品后查询角色"},
              {"role":"assistant","content":null,"tool_calls":[{"id":"old_query","type":"function","function":{"name":"forms_query","arguments":"{}"}}]},
              {"role":"tool","tool_call_id":"old_query","content":"商品查询完成"}],
             "tools":[{"type":"function","function":{"name":"roles_query","parameters":{"type":"object"}}}]}
            """;
        fixture.response(200,first("roles_query","{}")+end("tool_calls"));
        var result=fixture.transport.callTools("openrouter",OpenRouterTransport.ENDPOINT,"unit/model",10,"controlled-secret",wire,128,delivered::add,control);
        assertEquals(ExecutionStatus.SUCCEEDED,result.status());
        var captured=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(fixture.client).sendAsync(captured.capture(),any(HttpResponse.BodyHandler.class));
        var sent=json.readTree(new OpenRouterJsonObjectTest().requestBody(captured.getValue()));
        assertEquals(1,sent.path("tools").size());
        assertEquals("roles_query",sent.path("tools").path(0).path("function").path("name").asText());
    }
    @Test void mimoUsesItsOfficialEndpointAndStableToolModeWithCompleteUsage()throws Exception {
        fixture.response(200,first("records_mutate","{\"count\":0}")+end("tool_calls"));
        var preset=AiProviderPresets.require("mimo");
        var result=fixture.transport.callTools("mimo",preset.chatEndpoint(),"unit/model",10,"controlled-secret",request,128,delivered::add,control);
        assertEquals(ExecutionStatus.SUCCEEDED,result.status());assertEquals(12L,result.usage().totalTokens());assertFalse(delivered.isEmpty());
        var captured=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(fixture.client).sendAsync(captured.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions",captured.getValue().uri().toString());
        var body=json.readTree(new OpenRouterJsonObjectTest().requestBody(captured.getValue()));
        assertEquals(128,body.path("max_completion_tokens").asInt());assertFalse(body.has("max_tokens"));assertFalse(body.has("provider"));
        assertEquals("disabled",body.path("thinking").path("type").asText());assertTrue(body.path("stream_options").path("include_usage").asBoolean());
    }
    @Test void streamedFunctionNamesAreReassembledAndDuplicateCallIdsFail()throws Exception {
        fixture.response(200,first("records_","{")+frame(Map.of("tool_calls",List.of(Map.of("index",0,"function",Map.of("name","mutate","arguments","}")))),null)+end("tool_calls"));
        assertEquals(ExecutionStatus.SUCCEEDED,call().status());
        delivered.clear();
        var duplicate=Map.of("index",1,"id","call_fixture","type","function","function",Map.of("name","records_mutate","arguments","{}"));
        fixture.response(200,first("records_mutate","{}")+frame(Map.of("tool_calls",List.of(duplicate)),null)+end("tool_calls"));
        assertEquals(ErrorCode.INVALID_RESPONSE,call().errorCode());assertTrue(delivered.isEmpty());
    }
}
