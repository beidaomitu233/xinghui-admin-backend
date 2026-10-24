package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.ToolStreamObserver;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import java.util.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;

/** Bounded SDK wire exchange. No archive decoding, credential substitution or business execution. */
final class OpenAiToolWire {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(262144).maxNumberLength(256).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String,Object> body=new LinkedHashMap<>();
    private final Set<String> names=new HashSet<>();
    private final Map<Integer,Call> calls=new TreeMap<>();
    private final List<String> frames=new ArrayList<>();
    private final ToolStreamObserver observer;
    private int bytes;
    private boolean text,finished;
    private String terminalReason;
    private final StringBuilder textBuffer=new StringBuilder();
    private static final class Call {
        String id,type;
        final StringBuilder name=new StringBuilder();
        final StringBuilder arguments=new StringBuilder();
    }

    OpenAiToolWire(String request,ToolStreamObserver observer) {
        this.observer=Objects.requireNonNull(observer);
        JsonNode root=read(request,ErrorCode.INVALID_INPUT);
        if(!root.isObject()||!root.path("messages").isArray()||root.path("messages").isEmpty()||root.path("messages").size()>512)invalidInput();
        var tools=root.path("tools");
        if(!tools.isMissingNode()&&(!tools.isArray()||tools.size()>32))invalidInput();
        for(var tool:tools) {
            String name=tool.path("function").path("name").asText("");
            if(!"function".equals(tool.path("type").asText())||!validName(name)||!names.add(name)
                    ||!tool.path("function").path("parameters").isObject())invalidInput();
        }
        var pending=new HashSet<String>();var seen=new HashSet<String>();
        for(var message:root.path("messages")) {
            String role=message.path("role").asText("");
            if(!Set.of("system","user","assistant","tool").contains(role))invalidInput();
            if("tool".equals(role)) {
                if(!pending.remove(message.path("tool_call_id").asText("")))invalidInput();
            } else {
                if(!pending.isEmpty())invalidInput();
                var toolCalls=message.path("tool_calls");
                if(!toolCalls.isMissingNode()&&!toolCalls.isNull()) {
                    if(!"assistant".equals(role)||!toolCalls.isArray()||toolCalls.isEmpty()||toolCalls.size()>32)invalidInput();
                    for(var call:toolCalls) {
                        String id=call.path("id").asText("");
                        if(!validId(id)||!seen.add(id)||!"function".equals(call.path("type").asText())
                                ||!names.contains(call.path("function").path("name").asText()))invalidInput();
                        var arguments=call.path("function").path("arguments");
                        if(!arguments.isTextual()||!read(arguments.textValue(),ErrorCode.INVALID_INPUT).isObject())invalidInput();
                        pending.add(id);
                    }
                }
            }
        }
        if(!pending.isEmpty())invalidInput();
        body.put("messages",root.get("messages"));
        if(!tools.isMissingNode()&&!tools.isEmpty())body.put("tools",tools);
        var choice=root.get("tool_choice");
        if(choice!=null&&!choice.isNull()) {
            if(choice.isTextual()) {if(!Set.of("none","auto","required").contains(choice.textValue())||("required".equals(choice.textValue())&&names.isEmpty()))invalidInput();}
            else if(!choice.isObject()||!"function".equals(choice.path("type").asText())||!names.contains(choice.path("function").path("name").asText()))invalidInput();
            // "auto" is the protocol default. Some native-tool providers do not advertise tool_choice.
            if(!choice.isTextual()||!"auto".equals(choice.textValue()))body.put("tool_choice",choice);
        }
        // Wire formatting may suggest a model/options; the trusted configuration owns actual model and bounds.
        for(String option:List.of("temperature","top_p","presence_penalty","frequency_penalty")) {
            var value=root.get(option);if(value!=null&&!value.isNull()){if(!value.isNumber()||!Double.isFinite(value.doubleValue()))invalidInput();body.put(option,value);}
        }
        // SDK execution is serial. Do not require an optional upstream parallel_tool_calls parameter
        // that many otherwise capable free providers do not support.
    }
    Map<String,Object> body(){return Collections.unmodifiableMap(body);}
    boolean observe(JsonNode frame,String key) {
        var choices=frame.path("choices");
        if(!choices.isArray()||choices.size()>1)invalidResponse();
        var delta=choices.path(0).path("delta");
        // Some compatible providers repeat role/empty content and finish_reason on their usage trailer.
        // Accept metadata-only repeats, never new text, reasoning or tool fragments after a terminal frame.
        if(finished&&terminalPayload(delta))invalidResponse();
        var content=delta.get("content");
        if(content!=null&&!content.isNull()) {
            if(!content.isTextual())invalidResponse();
            if(!content.textValue().isEmpty()) {
                text=true;textBuffer.append(content.textValue());
                if(textBuffer.indexOf(key)>=0)invalidResponse();
                // Reserve a possible key prefix across text chunks. Tools stay withheld until complete validation.
                int take=Math.max(0,textBuffer.length()-key.length()+1);
                if(take>0){text(textBuffer.substring(0,take));textBuffer.delete(0,take);}
            }
        }
        var fragments=delta.get("tool_calls");boolean output=content!=null&&!content.asText("").isEmpty();
        if(fragments!=null&&!fragments.isNull()) {
            if(!fragments.isArray()||fragments.size()>32)invalidResponse();
            for(var fragment:fragments) {
                var index=fragment.get("index");if(index==null||!index.isIntegralNumber()||!index.canConvertToInt()||index.intValue()<0||index.intValue()>31)invalidResponse();
                var call=calls.computeIfAbsent(index.intValue(),ignored->new Call());
                call.id=once(call.id,fragment.get("id"));call.type=once(call.type,fragment.get("type"));
                var function=fragment.path("function");var name=function.get("name");
                if(name!=null&&!name.isNull()){if(!name.isTextual())invalidResponse();call.name.append(name.textValue());if(call.name.length()>64)invalidResponse();}
                var args=function.get("arguments");
                if(args!=null&&!args.isNull()){if(!args.isTextual())invalidResponse();call.arguments.append(args.textValue());if(call.arguments.length()>262144)throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);}
                output=true;
            }
        }
        String encoded=frame.toString();bytes+=encoded.length();
        if(bytes>1_048_576||frames.size()>=4096)throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
        frames.add(encoded);
        return output;
    }
    void finish(String reason) {
        if(finished){if(Objects.equals(terminalReason,reason))return;invalidResponse();}
        if("tool_calls".equals(reason)) {
            if(calls.isEmpty())invalidResponse();
            var ids=new HashSet<String>();int expected=0;
            for(var entry:calls.entrySet()) {
                var call=entry.getValue();
                if(entry.getKey()!=expected++||!validId(call.id)||!ids.add(call.id)||!"function".equals(call.type)||!names.contains(call.name.toString())
                        ||!read(call.arguments.toString(),ErrorCode.INVALID_RESPONSE).isObject())invalidResponse();
            }
        } else if(!calls.isEmpty()||!text)invalidResponse();
        finished=true;terminalReason=reason;
    }
    private static boolean terminalPayload(JsonNode delta){
        if(delta.isMissingNode()||delta.isNull())return false;
        if(!delta.isObject())return true;
        var fields=delta.fields();while(fields.hasNext()){
            var field=fields.next();var value=field.getValue();
            if(value.isNull()||value.isTextual()&&value.textValue().isEmpty()||value.isContainerNode()&&value.isEmpty())continue;
            if("role".equals(field.getKey())&&value.isTextual()&&"assistant".equals(value.textValue()))continue;
            return true;
        }return false;
    }
    boolean hasOutput(){return finished&&(text||!calls.isEmpty());}
    void deliver(String key,InvocationControl control) {
        // Only complete validated tool calls reach the SDK. Text may stream before a turn finishes.
        // Reject credential echoes rather than altering arguments (e.g. a legitimate field named "password").
        var lanes=new HashMap<String,StringBuilder>();
        for(var frame:frames)collect(read(frame,ErrorCode.INVALID_RESPONSE),"$",lanes);
        for(var lane:lanes.values())if(lane.indexOf(key)>=0)invalidResponse();
        for(var call:calls.values())if(call.arguments.indexOf(key)>=0||containsSecret(read(call.arguments.toString(),ErrorCode.INVALID_RESPONSE),key))invalidResponse();
        control.check();
        if(!textBuffer.isEmpty()){text(textBuffer.toString());textBuffer.setLength(0);}
        for(String frame:frames){
            control.check();var safe=(com.fasterxml.jackson.databind.node.ObjectNode)read(frame,ErrorCode.INVALID_RESPONSE);
            var delta=safe.path("choices").path(0).path("delta");
            if(delta.isObject())((com.fasterxml.jackson.databind.node.ObjectNode)delta).remove("content");
            observer.onFrame(safe.toString());
        }
    }
    private static void collect(JsonNode node,String path,Map<String,StringBuilder> lanes) {
        if(node.isTextual())lanes.computeIfAbsent(path,ignored->new StringBuilder()).append(node.textValue());
        else if(node.isObject())node.fields().forEachRemaining(entry->collect(entry.getValue(),path+"/"+entry.getKey(),lanes));
        else if(node.isArray())for(int i=0;i<node.size();i++)collect(node.get(i),path+"/"+i,lanes);
    }
    private static boolean containsSecret(JsonNode node,String key) {
        if(node.isTextual())return node.textValue().contains(key);
        if(node.isObject()) {
            var fields=node.fields();while(fields.hasNext()){var field=fields.next();if(field.getKey().contains(key)||containsSecret(field.getValue(),key))return true;}
        } else if(node.isArray())for(var child:node)if(containsSecret(child,key))return true;
        return false;
    }
    private static String once(String current,JsonNode node) {
        if(node==null||node.isNull())return current;
        if(!node.isTextual())invalidResponse();
        String value=node.textValue();if(value.isEmpty())return current;
        if(current!=null&&!current.equals(value))invalidResponse();return value;
    }
    private static boolean validName(String name){return name!=null&&name.matches("[A-Za-z0-9_-]{1,64}");}
    private void text(String value) {
        var frame=JSON.createObjectNode();frame.putArray("choices").addObject().put("index",0).putObject("delta").put("content",value);
        observer.onFrame(frame.toString());
    }
    private static boolean validId(String id){return id!=null&&id.matches("[A-Za-z0-9_.:-]{1,128}");}
    private static JsonNode read(String value,ErrorCode code) {
        try {if(value==null||value.length()>262144)throw new AiRuntimeException(code);var node=JSON.readTree(value);if(node==null)throw new AiRuntimeException(code);return node;}
        catch(AiRuntimeException safe){throw safe;}catch(Exception invalid){throw new AiRuntimeException(code);}
    }
    private static void invalidInput(){throw new AiRuntimeException(ErrorCode.INVALID_INPUT);}
    private static void invalidResponse(){throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);}
}
