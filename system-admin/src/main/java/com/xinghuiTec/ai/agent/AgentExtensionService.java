package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.exception.ServiceException;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.mcp.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.Duration;
import java.util.*;

@Service
public class AgentExtensionService {
    public record Entry(String id,String kind,String code,String name,String description,boolean enabled,JsonNode config,boolean secretSet,long version) {}
    public record Draft(String id,String kind,String code,String name,String description,Boolean enabled,JsonNode config,String secret,boolean clearSecret,Long version) {
        @Override public String toString(){return "AgentExtensionDraft[REDACTED]";}
    }
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final SecretCipher cipher;private final AgentAccess access;
    public AgentExtensionService(JdbcTemplate jdbc,ObjectMapper json,SecretCipher cipher,AgentAccess access){this.jdbc=jdbc;this.json=json;this.cipher=cipher;this.access=access;}
    public List<Entry> list(String tenant){
        return jdbc.query("SELECT * FROM sys_ai_agent_extension WHERE tenant_ref=? ORDER BY kind,name",(rs,n)->new Entry(rs.getString("extension_id"),rs.getString("kind"),rs.getString("code"),rs.getString("name"),rs.getString("description"),rs.getBoolean("enabled"),parse(rs.getString("config_json")),rs.getString("credential_ciphertext")!=null,rs.getLong("version")),tenant);
    }
    public List<Entry> builtins(){
        try(var repo=new ClasspathSkillRepository("hub-skills")){
            return repo.getAllSkills().stream().map(skill->new Entry("builtin:"+skill.getName(),"SKILL",skill.getName(),skill.getName(),skill.getDescription(),true,json.createObjectNode().put("content",skill.getSkillContent()),false,0)).toList();
        }catch(Exception failure){throw invalid("内置Skill读取失败");}
    }
    public Entry get(String tenant,String id){return list(tenant).stream().filter(row->row.id().equals(id)).findFirst().orElseThrow(()->invalid("扩展不存在"));}
    @Transactional
    public Entry save(TrustedActor actor,Draft draft){
        if(draft==null||draft.kind()==null||!Set.of("SKILL","MCP","KNOWLEDGE").contains(draft.kind())||draft.code()==null||!draft.code().matches("[a-z][a-z0-9_-]{1,63}")
                ||draft.name()==null||draft.name().isBlank()||draft.name().length()>100||draft.description()!=null&&draft.description().length()>1000
                ||draft.config()==null||!draft.config().isObject()||draft.config().toString().length()>65536||draft.secret()!=null&&draft.secret().length()>16384)throw invalid("请检查扩展名称、标识和配置");
        String tenant=actor.tenantId();
        Entry prior=draft.id()==null?null:get(tenant,draft.id());
        if(prior!=null&&(!prior.kind().equals(draft.kind())||!prior.code().equals(draft.code())))throw invalid("扩展类型和标识创建后不可修改");
        var spec=((ObjectNode)draft.config()).deepCopy();validate(draft.kind(),spec);
        String id=prior==null?UUID.randomUUID().toString():prior.id();
        String secret=prior==null?null:credential(tenant,id);
        if(draft.clearSecret())secret=null;
        else if(draft.secret()!=null&&!draft.secret().isBlank())secret=cipher.encrypt("ai:extension:"+id,draft.secret().trim());
        if(prior==null){
            if(list(tenant).stream().anyMatch(row->row.kind().equals(draft.kind())&&row.code().equals(draft.code())))throw invalid("同类型扩展标识已存在");
            jdbc.update("INSERT INTO sys_ai_agent_extension(extension_id,tenant_ref,kind,code,name,description,enabled,config_json,credential_ciphertext) VALUES(?,?,?,?,?,?,?,?,?)",id,tenant,draft.kind(),draft.code(),draft.name().trim(),Objects.toString(draft.description(),""),Boolean.TRUE.equals(draft.enabled()),spec.toString(),secret);
        }else if(draft.version()==null||jdbc.update("UPDATE sys_ai_agent_extension SET name=?,description=?,enabled=?,config_json=?,credential_ciphertext=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE extension_id=? AND tenant_ref=? AND version=?",
                draft.name().trim(),Objects.toString(draft.description(),""),Boolean.TRUE.equals(draft.enabled()),spec.toString(),secret,id,tenant,draft.version())!=1)throw invalid("配置已更新，请重新读取");
        return get(tenant,id);
    }
    private void validate(String kind,ObjectNode spec){
        if(kind.equals("SKILL")){
            String content=spec.path("content").asText();if(content.isBlank()||content.length()>32768)throw invalid("Skill内容需为1至32768个字符");
        }else{
            endpoint(spec.path("url").asText());
            if(kind.equals("MCP")){
                String header=spec.path("headerName").asText("Authorization");
                if(!header.matches("[A-Za-z][A-Za-z0-9-]{0,63}"))throw invalid("认证请求头格式不正确");
                if(spec.has("tools")&&(!spec.path("tools").isArray()||spec.path("tools").size()>200))throw invalid("请选择有效的MCP工具");
                for(var tool:spec.path("tools"))if(!tool.isTextual()||tool.asText().isBlank()||tool.asText().length()>200)throw invalid("MCP工具名称无效");
            }
            if(kind.equals("MCP")&&!Set.of("HTTP","SSE").contains(spec.path("transport").asText("HTTP")))throw invalid("请选择HTTP或SSE传输");
            if(kind.equals("KNOWLEDGE")){
                if(!Set.of("DIFY","FASTGPT").contains(spec.path("provider").asText())||!spec.path("datasetId").asText().matches("[A-Za-z0-9_-]{1,128}"))throw invalid("请选择知识库服务并填写数据集ID");
                int count=spec.path("topK").asInt(5);if(count<1||count>20)throw invalid("检索结果数需为1至20");
            }
        }
        String permission=spec.path("permission").asText();if(!permission.isBlank()&&!permission.matches("[A-Za-z][A-Za-z0-9:]*(?::[A-Za-z0-9]+)*"))throw invalid("调用权限标识格式不正确");
    }
    public static URI endpoint(String value){
        try{var uri=URI.create(value);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getQuery()!=null||uri.getHost().equals("169.254.169.254"))throw new IllegalArgumentException();return uri;}
        catch(RuntimeException invalid){throw invalid("请输入有效的HTTP服务地址，凭据请填写到密钥字段");}
    }
    String credential(String tenant,String id){return jdbc.queryForObject("SELECT credential_ciphertext FROM sys_ai_agent_extension WHERE tenant_ref=? AND extension_id=?",String.class,tenant,id);}
    public String secret(String tenant,Entry entry){String stored=credential(tenant,entry.id());return stored==null?null:cipher.decrypt("ai:extension:"+entry.id(),stored);}
    public boolean available(TrustedActor actor,Entry entry){String permission=entry.config().path("permission").asText();return entry.enabled()&&(permission.isBlank()||actor.authorities().contains(permission));}
    public List<Map<String,Object>> discover(TrustedActor actor,String id){
        var entry=get(actor.tenantId(),id);if(!entry.kind().equals("MCP"))throw invalid("仅MCP扩展支持工具发现");
        try(var client=client(actor.tenantId(),entry)){
            client.initialize().block(Duration.ofSeconds(15));
            var rows=client.listTools().block(Duration.ofSeconds(15));
            return rows==null?List.of():rows.stream().map(tool->{var row=new LinkedHashMap<String,Object>();row.put("name",tool.name());row.put("description",tool.description());row.put("readOnly",tool.annotations()!=null&&Boolean.TRUE.equals(tool.annotations().readOnlyHint()));row.put("inputSchema",tool.inputSchema());return (Map<String,Object>)row;}).toList();
        }catch(RuntimeException failed){throw invalid("MCP工具读取失败，请检查地址和认证设置");}
    }
    McpClientWrapper client(String tenant,Entry entry){
        var config=entry.config();var builder=McpClientBuilder.create(entry.code()).timeout(Duration.ofSeconds(20)).initializationTimeout(Duration.ofSeconds(10));
        if(config.path("transport").asText("HTTP").equals("SSE"))builder.sseTransport(config.path("url").asText());else builder.streamableHttpTransport(config.path("url").asText());
        String token=secret(tenant,entry);if(token!=null){String header=config.path("headerName").asText("Authorization");builder.header(header,header.equalsIgnoreCase("Authorization")?"Bearer "+token:token);}
        return builder.buildSync();
    }
    public AgentExtensionSet open(AgentCall call,String mode){
        return access.as(call.actor(),context->{
            var managed=new ArrayList<AgentSkill>();var mcps=new ArrayList<AgentExtensionSet.Mcp>();
            try{
                for(var entry:list(call.actor().tenantId()))if(available(context.actor(),entry)){
                    if(entry.kind().equals("SKILL"))managed.add(new AgentSkill(entry.code(),entry.description().isBlank()?entry.name():entry.description(),entry.config().path("content").asText(),Map.of(),"application"));
                    if(entry.kind().equals("MCP")){
                        List<String> tools=new ArrayList<>();entry.config().path("tools").forEach(tool->tools.add(tool.asText()));
                        var delegate=client(call.actor().tenantId(),entry);
                        mcps.add(new AgentExtensionSet.Mcp(new AuthorizedMcpClient(delegate,call,entry,this,access,"EXECUTE".equals(mode)),tools,entry.config().path("allowWrites").asBoolean(false)));
                    }
                }
                return new AgentExtensionSet(new ManagedSkillRepository(managed),mcps);
            }catch(Exception failure){mcps.forEach(connection->connection.client().close());throw new AgentProblem("EXTENSION_UNAVAILABLE","Agent扩展暂不可用，请检查接入设置");}
        });
    }
    private JsonNode parse(String value){try{return json.readTree(value);}catch(Exception invalid){throw invalid("扩展配置无法读取");}}
    private static ServiceException invalid(String message){return new ServiceException(message);}
}
