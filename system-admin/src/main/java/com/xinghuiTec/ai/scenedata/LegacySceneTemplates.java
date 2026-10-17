package com.xinghuiTec.ai.scenedata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.template.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Read exact immutable C-owned rows; never rewrites, publishes or approves a legacy definition. */
@Service
public class LegacySceneTemplates implements AiSceneLegacyTemplates {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final AiSceneDataRuntime scenes;
    public LegacySceneTemplates(JdbcTemplate jdbc,ObjectMapper json,AiSceneDataRuntime scenes){this.jdbc=jdbc;this.json=json;this.scenes=scenes;}

    @Override public LegacyView inspect(TemplateVersionRef version,Invocation legacy,InvocationControl control) {
        control.check();
        if(!legacy.actor().authorities().contains("ai:scene:query")&&!legacy.actor().authorities().contains("ai:scene:edit"))throw fail(ErrorCode.FORBIDDEN);
        scenes.catalog(legacy.actor(),control);
        var rows=jdbc.queryForList("SELECT v.config_json,t.enabled FROM sys_ai_prompt_template_version v JOIN sys_ai_prompt_template t ON t.id=v.template_id WHERE v.id=? AND v.template_id=?",version.templateVersionId(),version.templateId());
        if(rows.size()!=1)throw fail(ErrorCode.TEMPLATE_UNAVAILABLE);
        try {
            var original=json.readValue(String.valueOf(rows.get(0).get("config_json")),TemplateDefinition.class);TemplateRules.validate(original);
            var reasons=new LinkedHashSet<String>();var scopes=new ArrayList<LegacyScope>();var selections=new ArrayList<Selection>();
            if(!enabled(rows.get(0).get("enabled")))reasons.add("TEMPLATE_DISABLED");
            if(original.resources().size()>1)reasons.add("MULTIPLE_RESOURCES");
            var aliases=new HashMap<String,String>();var aliasTextCeilings=new HashMap<String,Integer>();
            for(var binding:original.resources()) {
                ResourceDefinition r;
                try {r=readResource(jdbc,json,binding.resourceId(),binding.resourceVersionId());}
                catch(AiRuntimeException unavailable) {reasons.add("RESOURCE_UNAVAILABLE");continue;}
                if(!r.fields().containsAll(binding.outputs().keySet()))reasons.add("RESOURCE_FIELDS_NOT_APPROVED");
                if(!r.source().equals("lowcode.record")||!ScenePromptCompiler.FIELDS.containsAll(binding.outputs().keySet()))reasons.add("UNSUPPORTED_RESOURCE_FIELDS");
                if(!r.applications().contains(legacy.appId())||!r.subjects().contains(legacy.actor().subjectId()))reasons.add("RESOURCE_SCOPE_DENIED");
                if(!binding.required()||binding.omitOnMissing()||!binding.parameters().equals(Map.of("formId",new TemplateDefinition.Parameter(TemplateDefinition.ParameterSource.BUSINESS_REF,"formId"),"recordId",new TemplateDefinition.Parameter(TemplateDefinition.ParameterSource.BUSINESS_REF,"recordId"))))reasons.add("SPECIAL_RESOURCE_MAPPING");
                var fields=List.copyOf(binding.outputs().keySet());
                scopes.add(new LegacyScope(new ResourceVersionRef(binding.resourceId(),binding.resourceVersionId()),legacy.appId(),legacy.appVersionId(),r.source(),r.formId(),fields));
                selections.add(new Selection(r.source(),r.formId(),fields));
                binding.outputs().forEach((field,var)->aliases.put(var,"record_"+field));
                try {
                    var metadata=scenes.fields(r.source(),r.formId(),legacy.actor(),control);
                    for(var output:binding.outputs().entrySet()) {
                        var field=metadata.fields().stream().filter(f->f.fieldKey().equals(output.getKey())).findFirst().orElse(null);
                        // LowcodeSchema's registered text/textarea ceilings cover every original record schema,
                        // unlike the latest field's configured maxLength, which may have changed over time.
                        int ceiling=field==null?0:switch(field.type()){case "text"->2000;case "textarea"->5000;default->0;};
                        if(ceiling==0)reasons.add("RESOURCE_VARIABLE_TYPE_POLICY_CHANGED");
                        else aliasTextCeilings.put(output.getValue(),ceiling);
                    }
                }catch(AiRuntimeException unavailable){reasons.add("RESOURCE_SCHEMA_UNAVAILABLE");}
            }
            for(var v:original.variables()) {
                if(v.hasDefault()||v.type()!=TemplateDefinition.Type.STRING)reasons.add("SPECIAL_VARIABLE_SCHEMA");
                if(v.truncate())reasons.add("VARIABLE_TRUNCATION_UNSUPPORTED");
                if(v.name().equals("user_request")) {
                    boolean referenced=original.messages().stream().anyMatch(m->ScenePromptCompiler.SLOT.matcher(m.text()).results().anyMatch(slot->slot.group(1).equals("user_request")));
                    if(v.required()!=referenced)reasons.add("INPUT_REQUIRED_POLICY_CHANGED");
                } else if(aliasTextCeilings.containsKey(v.name()) && v.maxChars()<aliasTextCeilings.get(v.name())) {
                    reasons.add("RESOURCE_VARIABLE_LENGTH_POLICY_CHANGED");
                }
                if(!v.name().equals("user_request")&&!aliases.containsKey(v.name()))reasons.add("CUSTOM_INPUT_VARIABLE");
                if(v.name().equals("user_request")&&v.maxChars()!=2048)reasons.add("INPUT_LENGTH_POLICY_CHANGED");
            }
            StringBuilder prompt=new StringBuilder();
            for(var m:original.messages()) {
                if(prompt.length()>0)prompt.append("\n\n");
                var matcher=ScenePromptCompiler.SLOT.matcher(m.text());var text=new StringBuffer();
                while(matcher.find())matcher.appendReplacement(text,java.util.regex.Matcher.quoteReplacement("{{"+aliases.getOrDefault(matcher.group(1),matcher.group(1))+"}}"));
                matcher.appendTail(text);prompt.append(text);
            }
            if(prompt.length()>32000)reasons.add("EDITOR_LENGTH_EXCEEDED");
            var editor=reasons.isEmpty()?new EditorInput(prompt.toString(),"",selections):null;
            Map<String,Object> raw=json.readValue(String.valueOf(rows.get(0).get("config_json")),new TypeReference<>(){});
            control.check();
            return new LegacyView(version,original.name(),reasons.isEmpty()?Compatibility.CONVERTIBLE:Compatibility.READ_ONLY,List.copyOf(reasons),true,
                    List.of("MESSAGE_SECTIONS_COMBINED_AS_USER","NULLS_PRESERVED_BY_BUILTIN_READER"),editor,
                    original.messages().stream().map(m->new LegacyMessage(m.section().name(),m.text())).toList(),new AiInputs(raw),scopes);
        }catch(com.fasterxml.jackson.core.JsonProcessingException|IllegalArgumentException invalid){throw fail(ErrorCode.TEMPLATE_UNAVAILABLE);}
    }

    @Override public CompiledScene compileEdited(TemplateVersionRef version,Invocation legacy,EditorInput edited,InvocationControl control) {
        var view=inspect(version,legacy,control);
        if(view.compatibility()!=Compatibility.CONVERTIBLE)throw fail(ErrorCode.CAPABILITY_UNSUPPORTED);
        // A user may narrow an approved legacy resource, never widen it by selecting a builtin checkbox.
        for(var selection:edited.selections())if(view.scopes().stream().noneMatch(s->s.objectKey().equals(selection.objectKey())&&s.formId().equals(selection.formId())&&s.fields().containsAll(selection.fields())))throw fail(ErrorCode.RESOURCE_FORBIDDEN);
        var compiled=scenes.compile(edited,legacy.actor(),control);
        var scopes=view.scopes().stream().filter(s->edited.selections().stream().anyMatch(x->s.objectKey().equals(x.objectKey())&&s.formId().equals(x.formId())))
                .map(s->new LegacyScope(s.resource(),s.applicationId(),s.applicationVersion(),s.objectKey(),s.formId(),edited.selections().stream().filter(x->s.objectKey().equals(x.objectKey())&&s.formId().equals(x.formId())).findFirst().orElseThrow().fields())).toList();
        return new CompiledScene(compiled.compilerVersion(),compiled.editor(),compiled.selections(),compiled.inputVariables(),scopes);
    }

    static ResourceDefinition readResource(JdbcTemplate jdbc,ObjectMapper json,String id,String version) {
        var rows=jdbc.queryForList("SELECT v.config_json,v.config_hash,v.approver,v.test_check_id,r.enabled FROM sys_ai_data_resource_version v JOIN sys_ai_data_resource r ON r.id=v.resource_id WHERE v.id=? AND v.resource_id=?",version,id);
        if(rows.size()!=1||!enabled(rows.get(0).get("enabled"))||rows.get(0).get("approver")==null)throw fail(ErrorCode.RESOURCE_UNAVAILABLE);
        var row=rows.get(0);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_data_resource_check WHERE id=? AND resource_id=? AND config_hash=? AND action='TEST' AND outcome='SUCCESS'",Long.class,row.get("test_check_id"),id,row.get("config_hash"))!=1L)throw fail(ErrorCode.RESOURCE_UNAVAILABLE);
        try {
            var resource=json.readValue(String.valueOf(row.get("config_json")),ResourceDefinition.class);TemplateRules.validate(resource);
            var bytes=json.writeValueAsBytes(canonical(json.valueToTree(resource),json));
            if(!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(row.get("config_hash")))throw fail(ErrorCode.RESOURCE_UNAVAILABLE);
            return resource;
        }
        catch(java.security.NoSuchAlgorithmException invalid){throw fail(ErrorCode.INTERNAL_FAILURE);}
        catch(com.fasterxml.jackson.core.JsonProcessingException|IllegalArgumentException invalid){throw fail(ErrorCode.RESOURCE_UNAVAILABLE);}
    }
    private static com.fasterxml.jackson.databind.JsonNode canonical(com.fasterxml.jackson.databind.JsonNode value,ObjectMapper json) {
        if(value.isObject()){var sorted=json.createObjectNode();var names=new TreeSet<String>();value.fieldNames().forEachRemaining(names::add);names.forEach(name->sorted.set(name,canonical(value.get(name),json)));return sorted;}
        if(value.isArray()){var result=json.createArrayNode();value.forEach(item->result.add(canonical(item,json)));return result;}return value;
    }
    private static boolean enabled(Object value){return Boolean.TRUE.equals(value)||"1".equals(String.valueOf(value));}
    private static AiRuntimeException fail(ErrorCode code){return new AiRuntimeException(code);}
}
