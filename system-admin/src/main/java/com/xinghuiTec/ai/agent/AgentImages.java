package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.persistence.JdbcAgentImages;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;

/** Image validation and private-reference hydration at the existing model gateway boundary. */
@Component
public class AgentImages {
    public record Wire(String json,boolean hasImages) { @Override public String toString(){return "AgentImageWire[REDACTED]";} }
    private final JdbcAgentImages images;
    private final ObjectMapper json;
    public AgentImages(JdbcAgentImages images,ObjectMapper json){this.images=images;this.json=json;}
    public JdbcAgentImages.Image upload(AgentActor actor,String session,MultipartFile file){
        if(file==null||file.isEmpty()||file.getSize()>JdbcAgentImages.MAX_IMAGE_BYTES)throw invalid();
        try{
            byte[] bytes=file.getBytes();String type;
            try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
                var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalid();
                var reader=readers.next();
                try{
                    reader.setInput(input);
                    String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    if(!Set.of("png","jpeg","jpg").contains(format))throw invalid();
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1||height<1||width>8192||height>8192||(long)width*height>16_000_000)throw invalid();
                    type="png".equals(format)?"image/png":"image/jpeg";
                    if(reader.read(0)==null)throw invalid();
                }finally{reader.dispose();}
            }
            return images.save(actor,session,file.getOriginalFilename(),type,bytes);
        }catch(AgentProblem failure){throw failure;}catch(Exception failure){throw invalid();}
    }
    public JdbcAgentImages.Stored read(AgentActor actor,String session,String id){return images.read(actor,session,id);}
    public Wire hydrate(AgentCall call,String wire){
        try{
            var root=json.readTree(wire);boolean found=false;
            var cache=new HashMap<String,String>();
            for(var message:root.path("messages"))for(var part:message.path("content")){
                if(!"image_url".equals(part.path("type").asText()))continue;
                String reference=part.path("image_url").path("url").asText();
                if(!reference.startsWith(JdbcAgentImages.REFERENCE_PREFIX))throw invalid();
                String id=reference.substring(JdbcAgentImages.REFERENCE_PREFIX.length());
                if(!UUID.fromString(id).toString().equals(id))throw invalid();
                String data=cache.computeIfAbsent(id,key->{
                    var image=images.read(call.actor(),call.sessionId(),key);
                    return "data:"+image.image().mediaType()+";base64,"+Base64.getEncoder().encodeToString(image.bytes());
                });
                ((ObjectNode)part.path("image_url")).put("url",data);found=true;
            }
            String result=found?json.writeValueAsString(root):wire;
            if(result.length()>8*1024*1024)throw new AgentProblem("IMAGE_CONTEXT_LIMIT","历史图片过多，请新建会话继续");
            return new Wire(result,found);
        }catch(AgentProblem failure){throw failure;}catch(Exception failure){throw invalid();}
    }
    private static AgentProblem invalid(){return new AgentProblem("INVALID_IMAGE","图片无法读取，请选择 PNG 或 JPG 图片重试");}
}
