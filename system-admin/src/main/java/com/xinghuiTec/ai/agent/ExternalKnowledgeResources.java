package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.xinghuiTec.agent.api.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Retrieval-only adapters; documents, indexing and embeddings stay in the external product. */
@Component
public class ExternalKnowledgeResources implements AgentResourceProvider {
    private final AgentExtensionService extensions;private final AgentAccess access;private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ExternalKnowledgeResources(AgentExtensionService extensions,AgentAccess access,ObjectMapper json){this.extensions=extensions;this.access=access;this.json=json;}
    public String namespace(){return "knowledge";}
    public JsonNode catalog(AgentCall call){
        var actor=access.current(call.actor()).actor();var rows=json.createArrayNode();
        for(var entry:extensions.list(call.actor().tenantId()))if(entry.kind().equals("KNOWLEDGE")&&extensions.available(actor,entry))rows.addObject()
                .put("resource","knowledge:"+entry.id()).put("name",entry.name()).put("description",entry.description());
        return rows;
    }
    private AgentExtensionService.Entry entry(AgentActor actor,String resource){
        var current=access.current(actor);if(resource==null||!resource.startsWith("knowledge:"))throw new AgentProblem("RESOURCE_UNAVAILABLE","请先选择已接入的知识库");
        AgentExtensionService.Entry entry;
        try{entry=extensions.get(actor.tenantId(),resource.substring("knowledge:".length()));}
        catch(com.xinghuiTec.exception.ServiceException missing){throw new AgentProblem("RESOURCE_UNAVAILABLE","知识库不存在或当前账号无权访问");}
        if(!entry.kind().equals("KNOWLEDGE")||!extensions.available(current.actor(),entry))throw new AgentProblem("FORBIDDEN","当前账号无权检索该知识库");return entry;
    }
    public void requireReadable(AgentActor actor,String resource){entry(actor,resource);}
    public JsonNode describe(AgentCall call,String resource){
        var entry=entry(call.actor(),resource);
        return json.valueToTree(Map.of("resource",resource,"name",entry.name(),"operations",List.of("query"),"parameters",Map.of("query","检索问题，必填","topK","可选；1至20条"),"provider",entry.config().path("provider").asText()));
    }
    public JsonNode query(AgentCall call,String resource,JsonNode args){
        call.check();var entry=entry(call.actor(),resource);var spec=entry.config();
        String query=args.path("query").asText(args.path("text").asText());int topK=args.path("topK").asInt(spec.path("topK").asInt(5));
        if(query.isBlank()||query.length()>4000||topK<1||topK>20)throw new AgentProblem("INVALID_ARGUMENTS","请输入检索问题，结果数为1至20");
        boolean dify=spec.path("provider").asText().equals("DIFY");
        String base=spec.path("url").asText().replaceAll("/+$","");
        String path=dify?"/datasets/"+spec.path("datasetId").asText()+"/retrieve":"/core/dataset/searchTest";
        ObjectNode body=json.createObjectNode();
        if(dify){body.put("query",query);body.set("retrieval_model",json.valueToTree(Map.of("search_method",spec.path("searchMethod").asText("semantic_search"),"reranking_enable",false,"top_k",topK,"score_threshold_enabled",false)));}
        else{body.put("datasetId",spec.path("datasetId").asText()).put("text",query).put("limit",spec.path("tokenLimit").asInt(5000)).put("similarity",spec.path("similarity").asDouble(0)).put("searchMode","embedding").put("usingReRank",false);}
        try{
            var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8));
            String secret=extensions.secret(call.actor().tenantId(),entry);if(secret!=null)request.header("Authorization","Bearer "+secret);
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());JsonNode result;
            try(var stream=response.body()){
                byte[] bytes=stream.readNBytes(1048577);if(response.statusCode()!=200||bytes.length>1048576)throw new IllegalStateException();result=json.readTree(bytes);
            }
            call.check();var records=dify?result.path("records"):result.path("data").path("list");
            if(!records.isArray())throw new IllegalStateException();
            var rows=json.createArrayNode();
            for(var record:records){
                if(rows.size()>=topK)break;
                JsonNode segment=dify?record.path("segment"):record;
                String content=dify?segment.path("content").asText():record.path("q").asText()+"\n"+record.path("a").asText();
                var row=rows.addObject();row.put("source",entry.name());row.put("document",dify?segment.path("document").path("name").asText():record.path("sourceName").asText());
                row.put("id",segment.path("id").asText(record.path("id").asText()));row.put("content",content.substring(0,Math.min(content.length(),2400)));
                if(record.has("score"))row.set("score",record.get("score"));
            }
            return json.createObjectNode().put("resource",resource).put("query",query).set("records",rows);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AgentProblem("KNOWLEDGE_UNAVAILABLE","知识库检索已中断");}
        catch(Exception failed){throw new AgentProblem("KNOWLEDGE_UNAVAILABLE","知识库检索未完成，请检查接入设置");}
    }
    public JsonNode mutate(AgentCall call,String resource,JsonNode arguments){throw new AgentProblem("READ_ONLY","外部知识库仅提供检索");}
}
