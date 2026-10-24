package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit native QA: exactly two bounded model turns, synthetic tool data, no business access. */
@EnabledIfEnvironmentVariable(named="XH_MIMO_TOOL_NATIVE_QA",matches="true")
class MimoToolNativeQaTest {
    @Test void paidFallbackCompletesOneSyntheticToolRoundTrip()throws Exception {
        var json=new ObjectMapper();
        var keys=Files.readAllLines(Path.of(System.getenv("XH_MIMO_TOOL_QA_KEY_FILE"))).stream().map(String::strip)
                .filter(line->line.matches("sk-[A-Za-z0-9_-]{12,}")).toList();
        assertEquals(1,keys.size(),"Expected the authorized MiMo key file");String key=keys.get(0);
        var preset=AiProviderPresets.require("mimo");var transport=new OpenRouterTransport(json,new AiModelConfigRules());
        var request=json.createObjectNode();
        var history=request.putArray("messages");history.addObject().put("role","user").put("content","Use read_fixture to read reference QA-ONLY-20261024, then answer only its returned status.");
        var function=request.putArray("tools").addObject().put("type","function").putObject("function");
        function.put("name","read_fixture").put("description","Read a synthetic QA fixture; no database or business operation.");
        var parameters=function.putObject("parameters").put("type","object").put("additionalProperties",false);
        parameters.putObject("properties").putObject("reference").put("type","string").putArray("enum").add("QA-ONLY-20261024");parameters.putArray("required").add("reference");
        var receipt=new LinkedHashMap<String,Object>();receipt.put("model","mimo-v2.6-flash");receipt.put("syntheticDataOnly",true);receipt.put("containsSecrets",false);
        var frames=new ArrayList<String>();
        var first=transport.callTools("mimo",preset.chatEndpoint(),"mimo-v2.6-flash",30,key,request.toString(),256,frames::add,control());
        receipt.put("tool",metrics(first));write(json,receipt);
        assertEquals(ExecutionStatus.SUCCEEDED,first.status(),"MiMo tool request did not succeed; inspect the safe receipt");
        String id="",name="";var arguments=new StringBuilder();
        for(String frame:frames)for(var part:json.readTree(frame).path("choices").path(0).path("delta").path("tool_calls")) {
            if(part.hasNonNull("id"))id=part.path("id").asText();
            name+=part.path("function").path("name").asText("");arguments.append(part.path("function").path("arguments").asText(""));
        }
        boolean valid=name.equals("read_fixture")&&!id.isBlank()&&!arguments.isEmpty()&&json.readTree(arguments.toString()).path("reference").asText().equals("QA-ONLY-20261024");
        receipt.put("fixtureArgumentsValid",valid);write(json,receipt);assertTrue(valid,"Expected the synthetic fixture tool call");
        var tool=history.addObject().put("role","assistant").putNull("content").putArray("tool_calls").addObject().put("id",id).put("type","function");
        tool.putObject("function").put("name",name).put("arguments",arguments.toString());
        history.addObject().put("role","tool").put("tool_call_id",id).put("content","{\"status\":\"QA_READY_20261024\"}");frames.clear();
        var second=transport.callTools("mimo",preset.chatEndpoint(),"mimo-v2.6-flash",30,key,request.toString(),256,frames::add,control());
        var text=new StringBuilder();for(String frame:frames)text.append(json.readTree(frame).path("choices").path(0).path("delta").path("content").asText(""));
        boolean complete=second.status()==ExecutionStatus.SUCCEEDED&&text.toString().contains("QA_READY_20261024");
        receipt.put("final",metrics(second));receipt.put("roundTrip",complete);receipt.put("completedAt",Instant.now().toString());write(json,receipt);
        assertTrue(complete,"MiMo did not complete the synthetic tool round trip; inspect the safe receipt");
        assertNotNull(first.usage().totalTokens());assertNotNull(second.usage().totalTokens());
        System.out.println("MIMO_TOOL_QA roundTrip=true");
    }
    private Map<String,Object> metrics(ExecutionOutcome result) {
        var data=new LinkedHashMap<String,Object>();data.put("status",result.status().name());data.put("elapsedMs",result.elapsedMs());
        data.put("totalTokens",result.usage().totalTokens());data.put("inputTokens",result.usage().inputTokens());data.put("outputTokens",result.usage().outputTokens());
        if(result.errorCode()!=null)data.put("errorCode",result.errorCode().name());
        if(result.telemetry()!=null){data.put("firstOutputMs",result.telemetry().firstOutputMs());data.put("httpStatus",result.telemetry().httpStatus());}
        return data;
    }
    private void write(ObjectMapper json,Map<String,Object> receipt)throws Exception {
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of(System.getenv("XH_MIMO_TOOL_QA_RECEIPT")).toFile(),receipt);
    }
    private InvocationControl control(){Instant bound=Instant.now().plusSeconds(35);return new InvocationControl(){public Instant deadline(){return bound;}public boolean cancelled(){return false;}};}
}
