package com.xinghuiTec.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Private immutable attachments. SDK checkpoints contain references, never embedded image bytes. */
public final class JdbcAgentImages {
    public static final int MAX_IMAGE_BYTES=1024*1024, MAX_IMAGES=4;
    public static final String REFERENCE_PREFIX="https://agent-image.internal/";
    public record Image(String id,String name,String mediaType,int size) {}
    public record Stored(Image image,byte[] bytes) { @Override public String toString(){return "StoredAgentImage[REDACTED]";} }
    private record Payload(String name,String mediaType,String base64) {}
    private final JdbcTemplate jdbc;
    private final JdbcAgentRepository repository;
    private final AgentPayloadCodec codec;
    private final ObjectMapper json;
    public JdbcAgentImages(JdbcTemplate jdbc,JdbcAgentRepository repository,AgentPayloadCodec codec,ObjectMapper json){
        this.jdbc=jdbc;this.repository=repository;this.codec=codec;this.json=json;
    }
    public Image save(AgentActor actor,String session,String name,String mediaType,byte[] bytes){
        repository.session(actor,session);
        if(bytes==null||bytes.length==0||bytes.length>MAX_IMAGE_BYTES||!Set.of("image/png","image/jpeg").contains(mediaType))
            throw new AgentProblem("INVALID_IMAGE","请选择不超过1MB的图片");
        String safeName=name==null?"图片":name.replaceAll("[\\p{Cntrl}\\\\/]", "_");
        if(safeName.length()>160)safeName=safeName.substring(safeName.length()-160);
        String id=UUID.randomUUID().toString();
        try{
            String payload=codec.seal(purpose(actor,session,id),json.writeValueAsString(new Payload(safeName,mediaType,Base64.getEncoder().encodeToString(bytes))));
            jdbc.update("INSERT INTO sys_agent_image(image_id,session_id,tenant_id,user_id,payload,created_at) VALUES(?,?,?,?,?,?)",
                    id,session,actor.tenantId(),actor.userId(),payload,Timestamp.from(Instant.now()));
            return new Image(id,safeName,mediaType,bytes.length);
        }catch(AgentProblem failure){throw failure;}catch(Exception failure){throw new AgentProblem("IMAGE_UNAVAILABLE","图片保存失败，请重试");}
    }
    public Stored read(AgentActor actor,String session,String id){
        repository.session(actor,session);
        var rows=jdbc.query("SELECT payload FROM sys_agent_image WHERE image_id=? AND session_id=? AND tenant_id=? AND user_id=?",
                (r,n)->r.getString(1),id,session,actor.tenantId(),actor.userId());
        if(rows.size()!=1)throw new AgentProblem("FORBIDDEN","无权读取此图片");
        try{
            var value=json.readValue(codec.open(purpose(actor,session,id),rows.get(0)),Payload.class);
            byte[] bytes=Base64.getDecoder().decode(value.base64());
            return new Stored(new Image(id,value.name(),value.mediaType(),bytes.length),bytes);
        }catch(Exception failure){throw new AgentProblem("IMAGE_UNAVAILABLE","图片无法读取");}
    }
    public List<Image> require(AgentActor actor,String session,List<String> ids){
        if(ids==null)return List.of();
        if(ids.size()>MAX_IMAGES||new HashSet<>(ids).size()!=ids.size())throw new AgentProblem("INVALID_IMAGE","每次最多添加4张不同图片");
        return ids.stream().map(id->read(actor,session,id).image()).toList();
    }
    private static String purpose(AgentActor actor,String session,String id){return "agent:image:"+actor.stateUserId()+":"+session+":"+id;}
}
