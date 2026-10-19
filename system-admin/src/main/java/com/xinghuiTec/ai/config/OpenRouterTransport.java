package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiCallTelemetry;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.xinghuiTec.ai.contract.AiTypes.*;

/** One bounded upstream call. No logging, provider fallback, arbitrary URLs, headers or retries. */
@Component
public class OpenRouterTransport {
    public static final String ENDPOINT="https://openrouter.ai/api/v1/chat/completions";
    private final ObjectMapper json;
    private final HttpClient client;
    private final boolean proxy;
    private final AiModelConfigRules addresses;
    private static final ObjectMapper STRICT_JSON=new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(256).maxStringLength(262144).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ScheduledExecutorService WATCH=Executors.newSingleThreadScheduledExecutor(r->{
        var t=new Thread(r,"ai-provider-cancel"); t.setDaemon(true); return t;
    });

    @Autowired
    public OpenRouterTransport(ObjectMapper json,AiModelConfigRules addresses) {
        this.json=json; this.addresses=addresses;
        String value=System.getenv("OPENROUTER_HTTP_PROXY");
        var builder=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10));
        proxy=value!=null&&!value.isBlank();
        if(proxy) {
            URI uri;
            try { uri=URI.create(value.trim()); }
            catch(RuntimeException invalid) { throw new IllegalStateException("OPENROUTER_HTTP_PROXY is invalid"); }
            if(!"http".equals(uri.getScheme())||uri.getHost()==null||uri.getPort()<1||uri.getPort()>65535
                    ||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getPath().isEmpty())
                throw new IllegalStateException("OPENROUTER_HTTP_PROXY is invalid");
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(),uri.getPort())));
        } else builder.proxy(new ProxySelector() {
            public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            public void connectFailed(URI uri,SocketAddress address,IOException error) {}
        });
        client=builder.build();
    }
    /** Package seam for controlled transport tests only; no production mock bean. */
    OpenRouterTransport(ObjectMapper json,AiModelConfigRules addresses,HttpClient client) {
        this.json=json;this.addresses=addresses;this.client=client;this.proxy=true;
    }

    ExecutionOutcome call(String modelCode,int timeoutSeconds,String key,List<AiMessage> messages,
                          Requirements requirements,int maxTokens,StreamObserver observer,InvocationControl control) {
        return callEndpoint("openrouter",ENDPOINT,modelCode,timeoutSeconds,key,messages,requirements,maxTokens,observer,control);
    }
    ExecutionOutcome callForProvider(String provider,String endpoint,String modelCode,int timeoutSeconds,String key,List<AiMessage> messages,
                          Requirements requirements,int maxTokens,StreamObserver observer,InvocationControl control) {
        if("openrouter".equals(provider) && ENDPOINT.equals(endpoint)) return call(modelCode,timeoutSeconds,key,messages,requirements,maxTokens,observer,control);
        AiProviderPresets.require(provider,endpoint);
        return callEndpoint(provider,endpoint,modelCode,timeoutSeconds,key,messages,requirements,maxTokens,observer,control);
    }
    private ExecutionOutcome callEndpoint(String provider,String endpoint,String modelCode,int timeoutSeconds,String key,List<AiMessage> messages,
                          Requirements requirements,int maxTokens,StreamObserver observer,InvocationControl control) {
        long start=System.nanoTime(); var state=new ParseState(key,observer,requirements.streaming(),requirements.outputFormat()==OutputFormat.JSON_OBJECT);
        var stream=new AtomicReference<InputStream>();
        var pending=new AtomicReference<CompletableFuture<?>>();
        Instant bound=Instant.now().plusSeconds(timeoutSeconds);
        Instant deadline=control.deadline().isBefore(bound)?control.deadline():bound;
        var watch=WATCH.scheduleAtFixedRate(()->{
            boolean abort;
            try { abort=control.cancelled()||!Instant.now().isBefore(deadline); }
            catch(RuntimeException unavailable) { abort=true; }
            if(abort) {
                var p=pending.get(); if(p!=null) p.cancel(true);
                close(stream.get());
            }
        },0,50,TimeUnit.MILLISECONDS);
        try {
            control.check();
            if(requirements.tools()||(requirements.outputFormat()==OutputFormat.JSON_OBJECT&&!OpenRouterCapabilities.supportsJson(modelCode))) throw new AiRuntimeException(ErrorCode.CAPABILITY_UNSUPPORTED);
            if(maxTokens<1||maxTokens>32768) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            if(key==null||key.isBlank()||key.length()>8192||key.chars().anyMatch(c->c<32||c==127)) throw new AiRuntimeException(ErrorCode.CREDENTIAL_UNAVAILABLE);
            if(!proxy) for(var address:InetAddress.getAllByName(URI.create(endpoint).getHost())) {
                String host=address.getHostAddress(); addresses.endpointUrl("https://"+(host.contains(":")?"["+host+"]":host));
            }
            var body=new LinkedHashMap<String,Object>();
            var outbound=new ArrayList<Map<String,String>>();
            if(state.jsonObject) outbound.add(Map.of("role","system","content","Return exactly one JSON object. Do not wrap it in Markdown."));
            outbound.addAll(messages.stream().map(m->Map.of("role",m.role().name().toLowerCase(Locale.ROOT),"content",m.content())).toList());
            body.put("model",modelCode); body.put("messages",outbound);
            body.put("stream",requirements.streaming());
            // OpenAI otherwise omits the final usage chunk; other supported providers report it without this opt-in.
            if("openai".equals(provider)&&requirements.streaming())body.put("stream_options",Map.of("include_usage",true));
            // OpenAI Chat Completions uses max_completion_tokens, including reasoning tokens.
            // https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
            body.put("openai".equals(provider)?"max_completion_tokens":"max_tokens",maxTokens);
            // These two catalog-verified models support optional reasoning; ordinary chat should reserve output for text.
            // https://openrouter.ai/docs/guides/best-practices/reasoning-tokens
            if("openrouter".equals(provider) && requirements.outputFormat()==OutputFormat.TEXT && Set.of("poolside/laguna-s-2.1:free","nvidia/nemotron-3.5-lightning:free").contains(modelCode))
                body.put("reasoning",Map.of("enabled",false));
            // Prevent routing to a different model; application-level fallback belongs to B.
            if("openrouter".equals(provider)) body.put("provider",state.jsonObject?Map.of("allow_fallbacks",false,"require_parameters",true):Map.of("allow_fallbacks",false));
            if("deepseek".equals(provider)) body.put("thinking",Map.of("type","disabled"));
            if(state.jsonObject) body.put("response_format",Map.of("type","json_object"));
            String encoded=json.writeValueAsString(body);
            if(encoded.length()>262144) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            control.check();
            if(!Instant.now().isBefore(deadline)) throw new AiRuntimeException(ErrorCode.DEADLINE_EXCEEDED);
            var request=HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(1,Duration.between(Instant.now(),deadline).toMillis())))
                    .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(encoded,StandardCharsets.UTF_8)).build();
            state.capture.sent();
            var future=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream()); pending.set(future);
            var response=future.get(Math.max(1,Duration.between(Instant.now(),deadline).toMillis()),TimeUnit.MILLISECONDS);
            try{state.capture.response(response.statusCode(),response.headers().map());}catch(RuntimeException unavailableHeaders){state.capture.unavailableHeaders(response.statusCode());}
            InputStream received=state.capture.receive(response.body());stream.set(received);
            if(response.statusCode()!=200){try{readError(received,state,control);}catch(IOException partialErrorBody){control.check();}return outcome(ExecutionStatus.FAILED,httpError(response.statusCode()),retryable(httpError(response.statusCode())),state,start);}
            if(requirements.streaming()) parseStream(received,state,modelCode,control);
            else parseJson(received,state,modelCode,control);
            if(!state.finished||!state.done||state.characters==0) throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
            if(state.jsonObject) state.prepareJsonObject();
            control.check();
            state.flush();
            return outcome(ExecutionStatus.SUCCEEDED,null,false,state,start);
        } catch(Exception failure) {
            ErrorCode code;
            try { code=control.cancelled()?ErrorCode.CANCELLED:!Instant.now().isBefore(deadline)?ErrorCode.DEADLINE_EXCEEDED:
                    failure instanceof AiRuntimeException safe?safe.code():failure instanceof TimeoutException?ErrorCode.DEADLINE_EXCEEDED:
                    failure instanceof com.fasterxml.jackson.core.JsonProcessingException?ErrorCode.INVALID_RESPONSE:
                    failure instanceof IOException||failure instanceof ExecutionException?ErrorCode.NETWORK_ERROR:ErrorCode.INVALID_RESPONSE; }
            catch(RuntimeException unavailable) { code=ErrorCode.DEPENDENCY_UNAVAILABLE; }
            // JSON fragments are withheld until a complete single object is validated and scrubbed.
            // An invalid/truncated object is never delivered as a successful structured result.
            if(!state.jsonObject) try { state.flush(); } catch(RuntimeException ignored) { /* No retry after observer failure. */ }
            ExecutionStatus status=code==ErrorCode.CANCELLED?ExecutionStatus.CANCELLED:code==ErrorCode.DEADLINE_EXCEEDED?ExecutionStatus.TIMED_OUT:
                    state.started()?ExecutionStatus.INCOMPLETE:ExecutionStatus.FAILED;
            return outcome(status,code,!state.started()&&retryable(code),state,start);
        } finally {
            watch.cancel(false); close(stream.get()); var p=pending.get(); if(p!=null&&!p.isDone()) p.cancel(true);
        }
    }
    private static boolean retryable(ErrorCode code) {
        return Set.of(ErrorCode.AUTH_FAILED,ErrorCode.RATE_LIMITED,ErrorCode.PROVIDER_UNAVAILABLE,
                ErrorCode.NETWORK_ERROR,ErrorCode.DEADLINE_EXCEEDED).contains(code);
    }
    private ExecutionOutcome outcome(ExecutionStatus status,ErrorCode error,boolean retry,ParseState s,long start) {
        boolean started=s.started();
        long elapsed=(System.nanoTime()-start)/1_000_000;
        AiCallTelemetry telemetry=s.capture.finish(status.name());
        return new ExecutionOutcome(status,error,retry&&status!=ExecutionStatus.CANCELLED&&!started,started,s.usage,s.providerId,elapsed,telemetry);
    }
    private static void close(InputStream stream) { if(stream!=null) try { stream.close(); } catch(IOException ignored) {} }
    private static ErrorCode httpError(int status) {
        return status==401?ErrorCode.AUTH_FAILED:status==403?ErrorCode.CONTENT_REJECTED:status==429?ErrorCode.RATE_LIMITED:
                status==408||status==504?ErrorCode.DEADLINE_EXCEEDED:status>=500?ErrorCode.PROVIDER_UNAVAILABLE:ErrorCode.INVALID_INPUT;
    }
    private void readError(InputStream stream,ParseState state,InvocationControl control)throws IOException{
        // Archive an error response under the existing receive bound, without treating it as model output.
        byte[] block=new byte[8192];int total=0,count;
        while(total<1_048_576&&(count=stream.read(block,0,Math.min(block.length,1_048_576-total)))!=-1){control.check();total+=count;}
        if(total<1_048_576)state.capture.bodyComplete();
    }
    private void parseJson(InputStream stream,ParseState state,String model,InvocationControl control) throws IOException {
        var bytes=new ByteArrayOutputStream(); byte[] block=new byte[8192]; int read;
        while((read=stream.read(block))!=-1) {
            if(read>0) state.unclassifiedBody=true;
            control.check();
            if(read>0) {
                // A failed partial non-streaming envelope may already contain generation output.
                // Until it can be classified, do not permit replay of that uncertain upstream call.
                state.unclassifiedBody=true;
                if(bytes.size()+read>1_048_576) throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
                bytes.write(block,0,read);
            }
        }
        control.check();
        state.capture.bodyComplete();state.capture.observe(bytes.toString(StandardCharsets.UTF_8));
        JsonNode data=state.jsonObject?STRICT_JSON.readTree(bytes.toByteArray()):json.readTree(bytes.toByteArray());
        state.unclassifiedBody=false;
        inspect(data,state,model,false);
        state.done=true;
    }
    private void parseStream(InputStream stream,ParseState state,String model,InvocationControl control) throws IOException {
        // Read character-by-character with explicit line/event/total bounds; readLine could allocate without limit.
        var reader=new InputStreamReader(stream,StandardCharsets.UTF_8);
        var line=new StringBuilder(); var event=new StringBuilder(); int count=0,ch;
        try{
        while((ch=reader.read())!=-1) {
            state.unclassifiedBody=true;
            control.check(); if(++count>1_048_576) throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
            if(ch=='\r') continue;
            if(ch!='\n') { line.append((char)ch); if(line.length()>65536) throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT); continue; }
            state.capture.line(line.toString());
            if(line.isEmpty()) {
                if(!event.isEmpty()) {
                    String payload=event.toString().stripTrailing(); event.setLength(0);
                    if("[DONE]".equals(payload)) { state.unclassifiedBody=false; state.done=true;state.capture.bodyComplete(); return; }
                    state.capture.observe(payload);
                    JsonNode frame=state.jsonObject?STRICT_JSON.readTree(payload):json.readTree(payload);
                    state.unclassifiedBody=false;
                    inspect(frame,state,model,true);
                } else state.unclassifiedBody=false;
            } else if(line.toString().startsWith("data:")) {
                event.append(line.substring(5).stripLeading()).append('\n');
                if(event.length()>65536) throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
            }
            line.setLength(0);
        }
        }finally{state.capture.partial(line.toString());}
        // EOF without the protocol terminator is incomplete even after a finish_reason frame.
    }
    private void inspect(JsonNode data,ParseState state,String model,boolean streaming) {
        if(data==null||!data.isObject()) throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
        var choice=data.path("choices").path(0);
        String content=choice.path(streaming?"delta":"message").path("content").asText("");
        // The upstream retry boundary precedes validation/redaction and observer delivery.
        // JSON and short TEXT deltas may still be buffered when the next read fails.
        if(!content.isEmpty()) state.receivedContent=true;
        if(data.has("error")) {
            int status=data.path("error").path("code").asInt(502);
            throw new AiRuntimeException(httpError(status));
        }
        String returned=data.path("model").asText("");
        if(!returned.isEmpty()&&!returned.replace(":free","").equals(model.replace(":free",""))) throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
        String providerId=data.path("id").asText("");
        if(providerId.matches("[A-Za-z0-9._:-]{1,128}")&&state.capture.safeMetadata(providerId,128)!=null) state.providerId=providerId;
        var usage=data.get("usage");
        if(usage!=null&&usage.isObject()) {
            state.usage=new Usage(number(usage,"prompt_tokens"),number(usage,"completion_tokens"),number(usage,"total_tokens"));
            state.observer.onUsage(state.usage);
        }
        String finish=choice.path("finish_reason").asText("");
        if(!content.isEmpty()) state.emit(content);
        if("stop".equals(finish)) state.finished=true;
        else if("length".equals(finish)) throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
        else if("content_filter".equals(finish)) throw new AiRuntimeException(ErrorCode.CONTENT_REJECTED);
        else if(!finish.isEmpty()) throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
    }
    private Long number(JsonNode usage,String field) {
        JsonNode n=usage.get(field); return n!=null&&n.isIntegralNumber()&&n.canConvertToLong()&&n.longValue()>=0?n.longValue():null;
    }
    private static final class ParseState {
        final String key; final StreamObserver observer; final boolean streaming,jsonObject;
        final AiResponseTelemetry capture;
        final StringBuilder buffer=new StringBuilder();
        boolean receivedContent,unclassifiedBody,output,finished,done; int characters; Usage usage=new Usage(null,null,null); String providerId;
        ParseState(String key,StreamObserver observer,boolean streaming,boolean jsonObject) { this.key=key;this.observer=observer;this.streaming=streaming;this.jsonObject=jsonObject;this.capture=new AiResponseTelemetry(streaming,key); }
        boolean started() { return receivedContent||unclassifiedBody||output; }
        void emit(String value) {
            if(!value.isEmpty()) receivedContent=true;
            characters+=value.length(); if(characters>262144) throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
            buffer.append(value);
            if(streaming&&!jsonObject) drain(false);
        }
        void prepareJsonObject() {
            try {
                JsonNode root=STRICT_JSON.readTree(buffer.toString());
                if(root==null||!root.isObject()) throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
                JsonNode safe=scrubJson(root);
                String encoded=STRICT_JSON.writeValueAsString(safe);
                if(encoded.length()>262144) throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
                buffer.setLength(0); buffer.append(encoded);
            } catch(AiRuntimeException classified) { throw classified; }
            catch(Exception invalid) { throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE); }
        }
        JsonNode scrubJson(JsonNode node) {
            if(node.isTextual()) return STRICT_JSON.getNodeFactory().textNode(node.textValue().replace(key,"[redacted]"));
            if(node.isObject()) {
                var result=STRICT_JSON.createObjectNode();
                node.fields().forEachRemaining(entry->{
                    String name=entry.getKey().replace(key,"[redacted]");
                    if(result.has(name)) throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
                    result.set(name,scrubJson(entry.getValue()));
                });
                return result;
            }
            if(node.isArray()) {
                var result=STRICT_JSON.createArrayNode(); node.forEach(item->result.add(scrubJson(item))); return result;
            }
            if(node.asText().contains(key)) throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
            return node;
        }
        void drain(boolean all) {
            if(buffer.isEmpty()) return;
            // Retain a possible secret prefix across provider chunk boundaries.
            String safe=jsonObject?buffer.toString():buffer.toString().replace(key,"[redacted]");
            int take=all?safe.length():Math.max(0,safe.length()-key.length());
            buffer.setLength(0); buffer.append(safe.substring(take));
            if(take>0) { output=true; observer.onDelta(safe.substring(0,take)); }
        }
        void flush() { drain(true); }
    }
}
