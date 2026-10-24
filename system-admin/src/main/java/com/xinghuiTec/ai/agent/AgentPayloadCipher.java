package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.agent.api.AgentPayloadCodec;
import com.xinghuiTec.agent.api.AgentProblem;
import com.xinghuiTec.config.SecretCipher;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Adapts the existing master-key cipher to multiline, bounded SDK state without a second key store. */
@Component
public final class AgentPayloadCipher implements AgentPayloadCodec {
    private static final int MAX_BYTES=4*1024*1024,CHUNK=24000;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(40000).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final SecretCipher cipher;
    public AgentPayloadCipher(SecretCipher cipher){this.cipher=Objects.requireNonNull(cipher);}
    @Override public String seal(String purpose,String text) {
        try {
            if(text==null)throw unavailable();
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_BYTES)throw new AgentProblem("STATE_LIMIT","会话数据过大，请新建会话。");
            String encoded=Base64.getEncoder().encodeToString(bytes),id=UUID.randomUUID().toString(),scope=scope(purpose);
            int count=Math.max(1,(encoded.length()+CHUNK-1)/CHUNK);
            var root=JSON.createObjectNode().put("version",1).put("id",id).put("bytes",bytes.length);
            var parts=root.putArray("parts");
            for(int i=0;i<count;i++) {
                String piece=encoded.substring(i*CHUNK,Math.min(encoded.length(),(i+1)*CHUNK));
                parts.add(cipher.encrypt(aad(scope,id,bytes.length,count,i),"b64:"+piece));
            }
            return JSON.writeValueAsString(root);
        }catch(AgentProblem safe){throw safe;}
        catch(Exception unavailable){throw new AgentProblem("ENCRYPTION_UNAVAILABLE","会话加密未就绪，请联系管理员。");}
    }
    @Override public String open(String purpose,String payload) {
        try {
            if(payload==null||payload.length()>8*1024*1024)throw unavailable();
            var root=JSON.readTree(payload);var parts=root.path("parts");
            if(!root.isObject()||root.size()!=4||root.path("version").asInt()!=1||!root.path("bytes").isIntegralNumber()||!root.path("bytes").canConvertToInt())throw unavailable();
            int size=root.path("bytes").intValue();
            if(size<0||size>MAX_BYTES||!parts.isArray()||parts.isEmpty()||parts.size()>256)throw unavailable();
            int encodedSize=((size+2)/3)*4,expected=Math.max(1,(encodedSize+CHUNK-1)/CHUNK);
            if(parts.size()!=expected)throw unavailable();
            String id=root.path("id").asText();if(!UUID.fromString(id).toString().equals(id))throw unavailable();
            String scope=scope(purpose);var encoded=new StringBuilder(encodedSize);
            for(int i=0;i<parts.size();i++) {
                if(!parts.get(i).isTextual()||parts.get(i).textValue().length()>40000)throw unavailable();
                String piece=cipher.decrypt(aad(scope,id,size,parts.size(),i),parts.get(i).textValue());
                if(!piece.startsWith("b64:")||piece.length()>CHUNK+4)throw unavailable();
                encoded.append(piece.substring(4));
            }
            if(encoded.length()!=encodedSize)throw unavailable();
            byte[] bytes=Base64.getDecoder().decode(encoded.toString());if(bytes.length!=size)throw unavailable();
            return new String(bytes,StandardCharsets.UTF_8);
        }catch(Exception unavailable){throw unavailable();}
    }
    private static String scope(String purpose)throws Exception {
        if(purpose==null||purpose.isBlank()||purpose.length()>2048)throw unavailable();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(purpose.getBytes(StandardCharsets.UTF_8)));
    }
    private static String aad(String scope,String id,int size,int count,int index){return "agent:payload:v1:"+scope+":"+id+":"+size+":"+count+":"+index;}
    private static AgentProblem unavailable(){return new AgentProblem("STATE_UNAVAILABLE","会话数据无法读取，请重新打开。");}
}
