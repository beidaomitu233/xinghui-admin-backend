package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.xinghuiTec.ai.contract.AiCallTelemetry;
import java.io.*;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Bounded, request-local receive observation. No outbound messages, persistence, retry, logging or callbacks to model code. */
final class AiResponseTelemetry {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(1048576).maxNumberLength(256).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final int LIMIT=AiCallTelemetry.MAX_ARCHIVE_BYTES,RAW_LIMIT=LIMIT+65536;
    private final boolean streaming;
    private final List<String> secrets;
    private final ByteArrayOutputStream raw=new ByteArrayOutputStream();
    private final List<ObjectNode> events=new ArrayList<>();
    private final List<String> lines=new ArrayList<>();
    private long startNanos,received,rawEventBytes;
    private Instant started;
    private Integer httpStatus;
    private Long headersMs,firstByte,firstOutput,firstText,lastOutput;
    private String firstKind,returnedModel,finishReason;
    private ObjectNode headers,usage;
    private JsonNode nonstreamBody;
    private boolean complete,truncated,redacted,failed,invalidJson,unsupportedCost;
    private String partialLine;
    AiResponseTelemetry(boolean streaming,String key){this.streaming=streaming;this.secrets=secretForms(key);}
    void sent(){started=Instant.now();startNanos=System.nanoTime();}
    private long now(){return Math.max(0,(System.nanoTime()-startNanos)/1_000_000);}
    void response(int status,Map<String,List<String>> values){
        try{httpStatus=status>=100&&status<=599?status:null;if(httpStatus==null)failed=true;headersMs=now();headers=JSON.createObjectNode();
            values.forEach((name,items)->{if(sensitive(name)){redacted=true;return;}headers.set(name,JSON.valueToTree(items));});
        }catch(Exception captureFailure){failed=true;}
    }
    void unavailableHeaders(int status){httpStatus=status>=100&&status<=599?status:null;if(headersMs==null)headersMs=now();failed=true;}
    InputStream receive(InputStream source){return new FilterInputStream(source){
        @Override public int read()throws IOException{int value=in.read();if(value>=0)bytes(new byte[]{(byte)value},0,1);return value;}
        @Override public int read(byte[] value,int offset,int length)throws IOException{int count=in.read(value,offset,length);if(count>0)bytes(value,offset,count);return count;}
    };}
    private void bytes(byte[] value,int offset,int count){
        if(firstByte==null)firstByte=now();received+=count;
        if(!streaming || (httpStatus!=null&&httpStatus!=200)){
            int keep=Math.min(count,RAW_LIMIT-raw.size());if(keep>0)raw.write(value,offset,keep);if(keep<count)truncated=true;
        }
    }
    void bodyComplete(){complete=true;}
    void line(String value){
        if(truncated||failed)return;
        try{
            if(value.isEmpty()){event(false);return;}
            lines.add(value);rawEventBytes+=value.getBytes(StandardCharsets.UTF_8).length+1;
            if(rawEventBytes>RAW_LIMIT||events.size()>=2048){truncated=true;lines.clear();}
        }catch(Exception captureFailure){failed=true;}
    }
    void partial(String value){if(value!=null&&!value.isEmpty())partialLine=value;}
    private void event(boolean partial)throws IOException{
        if(lines.isEmpty()&&(partialLine==null||partialLine.isEmpty()))return;
        var event=JSON.createObjectNode();event.put("sequence",events.size()+1);event.put("receivedMs",now());
        var fields=event.putArray("fields");var data=new StringBuilder();boolean hasData=false;
        for(String line:lines){
            int colon=line.indexOf(':');String name=colon<0?line:line.substring(0,colon),value=colon<0?"":line.substring(colon+1);
            if(value.startsWith(" "))value=value.substring(1);
            if(name.equals("data")){if(hasData)data.append('\n');data.append(value);hasData=true;}
            else if(sensitive(name)){redacted=true;}
            else{var field=fields.addObject();field.put("name",name);field.put("value",safeRaw(value));}
        }
        if(hasData){String text=data.toString();if("[DONE]".equals(text))event.put("data",text);
            else try{event.set("data",JSON.readTree(text));}catch(IOException invalid){invalidJson=true;event.put("data",safeRaw(text));}}
        if(partialLine!=null){event.put("partialLine",safeRaw(partialLine));partialLine=null;}
        if(partial)event.put("partial",true);events.add(event);lines.clear();
    }
    /** Observe received values before provider validation. Empty roles, heartbeat and usage are never model output. */
    void observe(String value){try{var node=JSON.readTree(value);if(!streaming)nonstreamBody=node;frame(node);}catch(Exception invalid){invalidJson=true;}}
    void frame(JsonNode data){
        try{
            if(data==null||!data.isObject())return;
            String model=data.path("model").asText("");if(!model.isEmpty())returnedModel=safeMetadata(model,256);
            var observed=data.get("usage");if(observed!=null&&observed.isObject()){if(usage==null)usage=JSON.createObjectNode();merge(usage,(ObjectNode)observed);}
            if(streaming)for(JsonNode choice:data.path("choices")){
                var delta=choice.path("delta");String kind=null;
                if(nonempty(delta.get("reasoning_content"))||nonempty(delta.get("reasoning"))||nonempty(delta.get("reasoning_details")))kind="REASONING";
                else if(nonempty(delta.get("content")))kind="TEXT";
                else if(nonempty(delta.get("tool_calls"))||nonempty(delta.get("function_call")))kind="TOOL_CALL";
                else if(nonempty(delta.get("refusal")))kind="REFUSAL";
                else if(nonempty(delta.get("audio")))kind="AUDIO";
                if(kind!=null){long time=now();if(firstOutput==null){firstOutput=time;firstKind=kind;}lastOutput=time;}
                if(nonempty(delta.get("content"))&&firstText==null)firstText=now();
            }
            for(JsonNode choice:data.path("choices")){var value=choice.get("finish_reason");if(value!=null&&!value.isNull()&&!value.asText().isEmpty())finishReason=safeMetadata(value.asText(),128);}
        }catch(RuntimeException captureFailure){failed=true;}
    }
    private static boolean nonempty(JsonNode value){return value!=null&&!value.isNull()&&(value.isTextual()?!value.textValue().isEmpty():(value.isArray()||value.isObject())?!value.isEmpty():false);}
    private static void merge(ObjectNode target,ObjectNode update){update.fields().forEachRemaining(e->{if(e.getValue().isObject()&&target.path(e.getKey()).isObject())merge((ObjectNode)target.get(e.getKey()),(ObjectNode)e.getValue());else if(!e.getValue().isNull())target.set(e.getKey(),e.getValue().deepCopy());});}
    String safeMetadata(String text,int maximum){String safe=safeText(text,false);return safe.equals(text)&&safe.length()<=maximum?safe:null;}
    private static boolean sensitive(String name){String n=name.replaceAll("[^A-Za-z0-9]","").toLowerCase(Locale.ROOT);
        return n.equals("key")||n.equals("keys")||n.equals("token")||n.equals("cookie")||n.equals("setcookie")||n.equals("authorization")||n.equals("proxyauthorization")
                ||n.contains("password")||n.contains("passwd")||n.contains("secret")||n.contains("credential")||n.contains("privatekey")||n.contains("apikey")
                ||n.equals("accesskey")||n.equals("accesskeyid")||n.endsWith("token")||n.contains("authentication")||n.contains("authorization")||n.contains("cookie");}
    private String safeText(String value,boolean partial){
        String safe=value;for(String secret:secrets)if(!secret.isEmpty()&&safe.contains(secret)){safe=safe.replace(secret,"[REDACTED]");redacted=true;}
        if(partial)for(String secret:secrets){
            if(!safe.isEmpty()&&secret.contains(safe)){safe="[REDACTED]";redacted=true;continue;}
            for(int length=Math.min(safe.length(),secret.length()-1);length>0;length--)if(safe.endsWith(secret.substring(0,length))){safe=safe.substring(0,safe.length()-length)+"[REDACTED]";redacted=true;break;}
        }
        return safe;
    }
    private String safeRaw(String value){
        // Unstructured/invalid JSON cannot safely preserve a credential-valued suffix. Decode escaped field names first.
        var decoded=new StringBuilder();for(int i=0;i<value.length();i++){
            if(value.charAt(i)=='\\'&&i+5<value.length()&&value.charAt(i+1)=='u')try{decoded.append((char)Integer.parseInt(value.substring(i+2,i+6),16));i+=5;continue;}catch(NumberFormatException ignored){}
            decoded.append(value.charAt(i));
        }
        String text=decoded.toString();var names=Pattern.compile("(?i)(?:[\\\"']?)([A-Za-z][A-Za-z0-9_ -]{0,64})(?:[\\\"']?)\\s*[:=]").matcher(text);
        while(names.find())if(sensitive(names.group(1).strip())){text=text.substring(0,names.end())+"[REDACTED]";redacted=true;break;}
        String bearer=text.replaceAll("(?i)\\bBearer\\s+[^\\s\\\"',;}]+","Bearer [REDACTED]");if(!bearer.equals(text))redacted=true;
        return safeText(bearer,true);
    }
    private JsonNode scrub(JsonNode value,boolean partial){
        if(value==null)return NullNode.instance;
        if(value.isTextual()){
            String text=value.textValue();
            if(text.startsWith("{")||text.startsWith("["))try{return TextNode.valueOf(JSON.writeValueAsString(scrub(JSON.readTree(text),partial)));}catch(IOException ignored){}
            return TextNode.valueOf(safeText(text,partial));
        }
        if(value.isObject()){var out=JSON.createObjectNode();value.fields().forEachRemaining(e->{if(sensitive(e.getKey())){redacted=true;return;}out.set(safeText(e.getKey(),partial),scrub(e.getValue(),partial));});return out;}
        if(value.isArray()){var out=JSON.createArrayNode();value.forEach(v->out.add(scrub(v,partial)));return out;}
        String text=value.toString();return secrets.stream().anyMatch(s->!s.isEmpty()&&text.contains(s))?TextNode.valueOf(safeText(text,partial)):value;
    }
    private record Slot(String text,Consumer<String> replace) {}
    private void slots(JsonNode node,String path,Map<String,List<Slot>> lanes){
        if(node==null)return;
        if(node.isObject())node.fields().forEachRemaining(e->{if(e.getValue().isTextual()){
            var slot=new Slot(e.getValue().textValue(),s->((ObjectNode)node).put(e.getKey(),s));lanes.computeIfAbsent(path+"/"+e.getKey(),k->new ArrayList<>()).add(slot);
            if(path.contains("/data/choices/")&&Set.of("content","reasoning_content","reasoning","refusal","arguments").contains(e.getKey()))lanes.computeIfAbsent("$MODEL_OUTPUT",k->new ArrayList<>()).add(slot);
        }else slots(e.getValue(),path+"/"+e.getKey(),lanes);});
        else if(node.isArray())for(int i=0;i<node.size();i++){int index=i;var v=node.get(i);if(v.isTextual())lanes.computeIfAbsent(path+"/"+i,k->new ArrayList<>()).add(new Slot(v.textValue(),s->((ArrayNode)node).set(index,TextNode.valueOf(s))));else slots(v,path+"/"+i,lanes);}
    }
    private void scrubSplitSecrets(){
        var lanes=new HashMap<String,List<Slot>>();for(var event:events)slots(event,"$",lanes);
        var redactions=new IdentityHashMap<Slot,BitSet>();
        for(var lane:lanes.values()){
            var text=new StringBuilder();for(var slot:lane)text.append(slot.text());var mask=new BitSet(text.length());
            for(String secret:secrets)if(!secret.isEmpty()){int index=0;while((index=text.indexOf(secret,index))>=0){mask.set(index,index+secret.length());index+=secret.length();}}
            if(mask.isEmpty())continue;redacted=true;int offset=0;
            for(var slot:lane){var selected=redactions.computeIfAbsent(slot,k->new BitSet());for(int i=0;i<slot.text().length();i++)if(mask.get(offset+i))selected.set(i);offset+=slot.text().length();}
        }
        redactions.forEach((slot,mask)->{var safe=new StringBuilder();boolean replacing=false;for(int i=0;i<slot.text().length();i++){if(mask.get(i)){if(!replacing)safe.append("[REDACTED]");replacing=true;}else{safe.append(slot.text().charAt(i));replacing=false;}}slot.replace().accept(safe.toString());});
    }
    private Long token(String... paths){for(String path:paths){var v=usage==null?null:usage.at(path);if(v!=null&&v.isIntegralNumber()&&v.canConvertToLong()&&v.longValue()>=0)return v.longValue();}return null;}
    private BigDecimal cost(){
        if(usage==null)return null;
        for(String path:List.of("/cost","/total_cost","/cost_details/total_cost")){
            var n=usage.at(path);if(n.isMissingNode()||n.isNull())continue;
            try{BigDecimal value=n.isNumber()?n.decimalValue():n.isTextual()&&n.textValue().length()<=128?new BigDecimal(n.textValue()):null;
                if(value!=null&&value.precision()<=128&&value.scale()>=-128&&value.scale()<=128&&((long)value.precision()-value.scale())<=128)return value;
            }catch(NumberFormatException ignored){}
            unsupportedCost=true;return null;
        }
        return null;
    }
    private String currency(){if(usage==null)return null;String value=usage.path("currency").asText("");return value.matches("[A-Za-z]{3}")?safeMetadata(value,3):null;}
    AiCallTelemetry finish(String outcome){
        Instant end=Instant.now();Long elapsed=started==null?null:now();
        String body=null,headerJson=null,usageJson=null,format=null,status="NOT_CAPTURED",reason="NO_UPSTREAM";long archived=0;
        try{
            if(started!=null){
                boolean partial=!complete;
                if(streaming&&(httpStatus==null||httpStatus==200)){
                    if(!truncated){event(true);if(!events.isEmpty()&&events.get(events.size()-1).path("partial").asBoolean())partial=true;}
                    scrubSplitSecrets();var values=JSON.createArrayNode();for(var event:events)values.add(scrub(event,partial||truncated));
                    format="SSE";body=JSON.writeValueAsString(values);
                }else if(received>0){
                    String text=raw.toString(StandardCharsets.UTF_8);try{var node=nonstreamBody==null?JSON.readTree(text):nonstreamBody;body=JSON.writeValueAsString(scrub(node,partial||truncated));format="JSON";
                        if(usage==null&&node!=null&&node.path("usage").isObject())usage=(ObjectNode)node.get("usage").deepCopy();
                    }catch(IOException invalid){invalidJson=true;body=safeRaw(text);format="TEXT";}
                }
                if(headers!=null)headerJson=JSON.writeValueAsString(scrub(headers,partial||truncated));
                if(usage!=null)usageJson=JSON.writeValueAsString(scrub(usage,partial||truncated));
                // Truncate only after recursive and cross-frame scrubbing; keep complete JSON/SSE values or safe UTF-8 text.
                if(bytes(headerJson)+bytes(usageJson)>LIMIT){headerJson=null;usageJson=null;truncated=true;}
                int budget=LIMIT-(int)(bytes(headerJson)+bytes(usageJson));
                if(bytes(body)>budget){truncated=true;
                    if("SSE".equals(format)){var array=(ArrayNode)JSON.readTree(body);while(!array.isEmpty()&&bytes(array.toString())>budget)array.remove(array.size()-1);body=array.toString();}
                    else if("TEXT".equals(format)){int endIndex=Math.min(body.length(),budget);body=safeRaw(body.substring(0,endIndex));while(bytes(body)>budget&&!body.isEmpty())body=safeRaw(body.substring(0,Math.max(0,body.length()-256)));}
                    else body=JSON.writeValueAsString(boundedJson(JSON.readTree(body),budget));
                }
                archived=bytes(headerJson)+bytes(usageJson)+bytes(body);
                status=failed?"FAILED":truncated||invalidJson||partial?"PARTIAL":"COMPLETE";
                reason=failed?"ARCHIVE_FAILED":truncated?"CAPTURE_LIMIT":invalidJson?"INVALID_JSON":partial?"CANCELLED".equals(outcome)?"CANCELLED":"TRANSPORT_PARTIAL":null;
                if(received==0&&headers==null){status="NOT_CAPTURED";reason="NO_RESPONSE";body=null;format=null;archived=bytes(usageJson);}
            }
            BigDecimal reported=cost();if(unsupportedCost&&reason==null)reason="UNSUPPORTED_COST";
            return new AiCallTelemetry(1,streaming,started,started==null?null:end,httpStatus,elapsed,headersMs,firstByte,firstOutput,firstText,lastOutput,firstKind,returnedModel,finishReason,
                    token("/prompt_tokens","/input_tokens"),token("/completion_tokens","/output_tokens"),token("/total_tokens"),
                    token("/prompt_tokens_details/cached_tokens","/input_tokens_details/cached_tokens","/cache_read_input_tokens","/prompt_cache_hit_tokens"),
                    token("/prompt_tokens_details/cache_write_tokens","/input_tokens_details/cache_creation_tokens","/cache_creation_input_tokens","/cache_write_tokens"),
                    token("/completion_tokens_details/reasoning_tokens","/output_tokens_details/reasoning_tokens","/reasoning_tokens"),reported,currency(),usageJson,headerJson,body,format,status,reason,started==null?null:received,archived,redacted,truncated);
        }catch(Exception archiveFailure){return new AiCallTelemetry(1,streaming,started,started==null?null:end,httpStatus,elapsed,headersMs,firstByte,firstOutput,firstText,lastOutput,firstKind,returnedModel,finishReason,
                null,null,null,null,null,null,null,null,null,null,null,null,"FAILED","ARCHIVE_FAILED",started==null?null:received,0L,redacted,truncated);}
    }
    private static long bytes(String value){return value==null?0:value.getBytes(StandardCharsets.UTF_8).length;}
    private JsonNode boundedJson(JsonNode node,int budget){
        if(budget<16)return NullNode.instance;
        if(bytes(node.toString())<=budget)return node;
        if(node.isTextual()){
            String text=node.textValue();int end=Math.min(text.length(),Math.max(0,(budget-32)/6));if(end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;
            return TextNode.valueOf(safeText(text.substring(0,end),true)+"[TRUNCATED]");
        }
        if(node.isObject()){
            var out=JSON.createObjectNode();int each=Math.max(16,budget/Math.max(1,Math.min(16,node.size())));var fields=node.fields();
            while(fields.hasNext()){var entry=fields.next();int remaining=budget-(int)bytes(out.toString())-(int)bytes(entry.getKey())-8;if(remaining<16)break;out.set(entry.getKey(),boundedJson(entry.getValue(),Math.min(each,remaining)));if(bytes(out.toString())>budget){out.remove(entry.getKey());break;}}
            return out;
        }
        if(node.isArray()){
            var out=JSON.createArrayNode();int each=Math.max(16,budget/Math.max(1,Math.min(16,node.size())));
            for(var child:node){int remaining=budget-(int)bytes(out.toString())-2;if(remaining<16)break;out.add(boundedJson(child,Math.min(each,remaining)));if(bytes(out.toString())>budget){out.remove(out.size()-1);break;}}return out;
        }
        return NullNode.instance;
    }
    private static List<String> secretForms(String key){if(key==null||key.isEmpty()||key.length()>8192)return List.of();var values=new LinkedHashSet<String>();values.add(key);
        try{String encoded=JSON.writeValueAsString(key);values.add(encoded.substring(1,encoded.length()-1));}catch(IOException ignored){}
        values.add(URLEncoder.encode(key,StandardCharsets.UTF_8));values.add(Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)));values.add(Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8)));
        var unicode=new StringBuilder();for(char c:key.toCharArray())unicode.append(String.format(Locale.ROOT,"\\u%04x",(int)c));values.add(unicode.toString());return values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();}
}
