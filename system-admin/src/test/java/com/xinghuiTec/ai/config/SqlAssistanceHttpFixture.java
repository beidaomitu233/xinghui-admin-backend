package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import static org.mockito.Mockito.*;

/** Only the HTTP boundary is controlled; the configured provider and executor remain real. */
public final class SqlAssistanceHttpFixture {
    public final OpenRouterTransport transport;
    public final AtomicInteger generations=new AtomicInteger();
    public final List<String> generationModels=new CopyOnWriteArrayList<>();
    public final List<Integer> schemaChecksAtAttempt=new CopyOnWriteArrayList<>();
    public volatile IntSupplier schemaChecks=()->0;
    public volatile String output="{\"sql\":\"SELECT f.subject FROM form_1 f WHERE f.subject = :term\",\"parameters\":{\"term\":\"Alpha\"}}";
    public volatile String systemMessage;
    public volatile boolean echoSystem,echoInParameter,partial,hold;
    public volatile int primaryStatus=200,secondaryStatus=200;
    public volatile CompletableFuture<HttpResponse<InputStream>> pending;
    public final CountDownLatch entered=new CountDownLatch(1);
    private final ObjectMapper mapper;
    private volatile HttpResponse<InputStream> heldResponse;

    public SqlAssistanceHttpFixture(ObjectMapper mapper) {
        this.mapper=mapper;var client=mock(HttpClient.class);
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{
            HttpRequest request=call.getArgument(0);var body=mapper.readTree(body(request));String model=body.path("model").asText();
            boolean stream=body.path("stream").asBoolean();String material=request.headers().firstValue("Authorization").orElseThrow();
            if(!stream)return CompletableFuture.completedFuture(response(200,mapper.writeValueAsString(Map.of("model",model,"choices",List.of(Map.of("message",Map.of("content","OK"),"finish_reason","stop"))))));
            generations.incrementAndGet();generationModels.add(model);schemaChecksAtAttempt.add(schemaChecks.getAsInt());systemMessage=body.path("messages").get(0).path("content").asText();
            int status=material.contains("primary")?primaryStatus:secondaryStatus;
            String text=echoSystem?systemMessage:echoInParameter?mapper.writeValueAsString(Map.of("sql","SELECT f.subject FROM form_1 f WHERE f.subject = :term","parameters",Map.of("term",systemMessage))):output;
            String payload="data: "+mapper.writeValueAsString(Map.of("model",model,"choices",List.of(Map.of("delta",Map.of("content",text),"finish_reason","stop"))))+"\n\n"+(partial?"":"data: [DONE]\n\n");
            var response=response(status,status==200?payload:"{}");
            if(hold){heldResponse=response;pending=new CompletableFuture<>();entered.countDown();return pending;}
            entered.countDown();return CompletableFuture.completedFuture(response);
        });
        transport=new OpenRouterTransport(mapper,new AiModelConfigRules(),client);
    }
    public void release(){var future=pending;if(future!=null)future.complete(heldResponse);}
    private HttpResponse<InputStream> response(int status,String text) {
        HttpResponse<InputStream> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));return response;
    }
    private String body(HttpRequest request)throws Exception {
        var result=new CompletableFuture<String>();var bytes=new ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer buffer){byte[] b=new byte[buffer.remaining()];buffer.get(b);bytes.writeBytes(b);}
            public void onError(Throwable failure){result.completeExceptionally(failure);}
            public void onComplete(){result.complete(bytes.toString(StandardCharsets.UTF_8));}
        });return result.get(2,TimeUnit.SECONDS);
    }
}
