package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly enabled native QA. Only fixed synthetic fixture data is ever sent upstream. */
@EnabledIfEnvironmentVariable(named="XH_AGENT_TOOL_NATIVE_QA",matches="true")
class OpenRouterToolNativeQaTest {
    @Test void freeModelsExecuteOneSyntheticToolRoundTripThroughProductionTransport()throws Exception {
        var json=new ObjectMapper();String key=Files.readString(Path.of(System.getenv("XH_AGENT_TOOL_QA_KEY_FILE"))).strip();
        assertTrue(key.matches("sk-or-v1-[A-Za-z0-9_-]{16,}"),"The authorized key file must contain only one key");
        var models=List.of(System.getenv("XH_AGENT_TOOL_QA_MODELS").split(","));
        assertTrue(models.size()>=3&&models.size()<=7&&models.stream().allMatch(model->model.matches("[A-Za-z0-9_./-]+:free")));
        var transport=new OpenRouterTransport(json,new AiModelConfigRules());
        var results=new ArrayList<Map<String,Object>>();int successes=0;
        for(String model:models) {
            var result=new LinkedHashMap<String,Object>();result.put("model",model);result.put("completedAt",Instant.now().toString());
            var messages=json.createArrayNode();messages.addObject().put("role","user").put("content","Use read_fixture to read reference QA-ONLY-20261024. Then answer only its status.");
            var request=json.createObjectNode();request.set("messages",messages);
            var function=request.putArray("tools").addObject().put("type","function").putObject("function");
            function.put("name","read_fixture");function.put("description","Read one synthetic QA fixture; no database access or real business operation.");
            var parameters=function.putObject("parameters").put("type","object").put("additionalProperties",false);
            parameters.putObject("properties").putObject("reference").put("type","string").putArray("enum").add("QA-ONLY-20261024");parameters.putArray("required").add("reference");
            request.put("tool_choice","auto");
            var frames=new ArrayList<String>();
            var first=transport.callTools("openrouter",OpenRouterTransport.ENDPOINT,model,25,key,json.writeValueAsString(request),1024,frames::add,control());
            result.put("toolStatus",first.status().name());result.put("toolElapsedMs",first.elapsedMs());
            if(first.errorCode()!=null)result.put("toolError",first.errorCode().name());
            if(first.telemetry()!=null&&first.telemetry().httpStatus()!=null)result.put("toolHttpStatus",first.telemetry().httpStatus());
            if(first.status()!=ExecutionStatus.SUCCEEDED&&first.telemetry()!=null&&first.telemetry().responseBody()!=null) {
                // The production capture has already scrubbed credentials. Keep this synthetic-only diagnostic local.
                result.put("safeErrorBody",first.telemetry().responseBody());
            }
            if(first.telemetry()!=null&&first.telemetry().firstOutputMs()!=null)result.put("toolFirstOutputMs",first.telemetry().firstOutputMs());
            result.put("toolTokens",first.usage().totalTokens()==null?"unknown":first.usage().totalTokens());
            if(first.status()==ExecutionStatus.SUCCEEDED) {
                String id="",name="";var args=new StringBuilder();
                for(String frame:frames)for(var delta:json.readTree(frame).path("choices").path(0).path("delta").path("tool_calls")) {
                    if(delta.hasNonNull("id"))id=delta.path("id").asText();
                    name+=delta.path("function").path("name").asText("");args.append(delta.path("function").path("arguments").asText(""));
                }
                boolean valid=name.equals("read_fixture")&&!args.isEmpty()&&json.readTree(args.toString()).path("reference").asText().equals("QA-ONLY-20261024");
                result.put("fixtureArgumentsValid",valid);
                if(valid) {
                    var history=(com.fasterxml.jackson.databind.node.ArrayNode)request.get("messages");
                    var call=history.addObject().put("role","assistant").putNull("content").putArray("tool_calls").addObject().put("id",id).put("type","function");
                    call.putObject("function").put("name",name).put("arguments",args.toString());
                    history.addObject().put("role","tool").put("tool_call_id",id).put("content","{\"reference\":\"QA-ONLY-20261024\",\"status\":\"READY\"}");
                    request.put("tool_choice","auto");frames.clear();
                    var second=transport.callTools("openrouter",OpenRouterTransport.ENDPOINT,model,25,key,json.writeValueAsString(request),1024,frames::add,control());
                    var text=new StringBuilder();for(String frame:frames)text.append(json.readTree(frame).path("choices").path(0).path("delta").path("content").asText(""));
                    boolean roundTrip=second.status()==ExecutionStatus.SUCCEEDED&&text.toString().strip().replace("\"","").equalsIgnoreCase("READY");
                    result.put("finalStatus",second.status().name());result.put("finalElapsedMs",second.elapsedMs());result.put("roundTrip",roundTrip);
                    if(second.errorCode()!=null)result.put("finalError",second.errorCode().name());
                    if(roundTrip)successes++;
                }
            }
            results.add(result);
            // This file contains only fixed classifications and counts, never request bodies or the key.
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of(System.getenv("XH_AGENT_TOOL_QA_RECEIPT")).toFile(),Map.of("containsSecrets",false,"syntheticDataOnly",true,"models",results));
            System.out.println("FREE_TOOL_QA "+model+" "+result.getOrDefault("roundTrip",false));
        }
        assertTrue(successes>=3,"At least three current free models must complete the synthetic tool round trip; inspect the safe receipt");
    }
    private InvocationControl control(){Instant deadline=Instant.now().plusSeconds(30);return new InvocationControl(){public Instant deadline(){return deadline;}public boolean cancelled(){return false;}};}
}
