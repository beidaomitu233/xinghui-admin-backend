package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.lowcode.LowcodeSchema;
import com.xinghuiTec.lowcode.LowcodeService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Every published form shares this adapter; adding a form requires no new agent workflow. */
@Component
public class LowcodeAgentResources implements AgentResourceProvider {
    private final LowcodeService forms;private final AgentAccess access;private final ObjectMapper json;
    public LowcodeAgentResources(LowcodeService forms,AgentAccess access,ObjectMapper json){this.forms=forms;this.access=access;this.json=json;}
    public String namespace(){return "forms";}
    public JsonNode catalog(AgentCall call){
        var entries=json.createArrayNode();var actor=access.current(call.actor());
        if(actor.platform()&&actor.has("lowcode:form:list")){
            var entry=entries.addObject().put("resource","forms").put("name","业务表单")
                .put("description","查询已授权表单目录；记录操作仅适用于已发布、当前账号可访问的表单，以具体表单定义为准。");
            entry.putArray("operations").add("query");var records=entry.putArray("recordOperations");
            if(actor.has("lowcode:record:list"))records.add("query");if(actor.has("lowcode:record:query"))records.add("get");
            for(String action:List.of("create","update","delete"))if(actor.has(permission(action))){records.add(action);records.add("bulk"+Character.toUpperCase(action.charAt(0))+action.substring(1));}
        }
        return entries;
    }
    public void requireReadable(AgentActor actor,String resource){
        var context=access.current(actor);
        if(resource.equals("forms")){context.requirePlatform();context.require("lowcode:form:list");return;}
        formId(resource);if(!context.has("lowcode:record:list")&&!context.has("lowcode:record:query"))throw AgentAccess.denied();
    }
    public JsonNode describe(AgentCall call,String resource){
        requireReadable(call.actor(),resource);
        if(resource.equals("forms"))return json.valueToTree(Map.of("resource",resource,"description","先查询目录中的resource值；forms:<id>中的id是目录返回的数字ID，不是formKey。","operations",List.of("query"),"query",Map.of("pageNum","正整数，默认1","pageSize","1至100，默认20","search","表单名称或formKey；可省略以列出目录","orderBy","id/name/updateTime","order","asc/desc")));
        var runtime=forms.runtime(formId(resource));var context=access.current(call.actor());var result=json.createObjectNode();
        result.put("resource",resource).put("name",String.valueOf(runtime.get("name"))).put("schemaVersion",((Number)runtime.get("schemaVersion")).intValue());
        result.set("schema",json.valueToTree(runtime.get("schema")));var operations=result.putArray("operations");
        if(context.has("lowcode:record:list"))operations.add("query");if(context.has("lowcode:record:query"))operations.add("get");
        for(String action:List.of("create","update","delete"))if(context.has(permission(action))){operations.add(action);operations.add("bulk"+Character.toUpperCase(action.charAt(0))+action.substring(1));}
        result.set("query",json.valueToTree(Map.of("id","可选；单条记录ID字符串，需query权限","pageNum","默认1","pageSize","1至100，默认20","filterField","schema中searchable=true的字段","filterValue","筛选值字符串","orderBy","id/createTime/updateTime或data.<可排序字段>","order","asc/desc","fields","可选字段标识数组；省略时返回全部字段")));
        result.set("mutation",json.valueToTree(Map.of("operation","上述可用操作之一","schemaVersion","create/update必填，来自当前资源定义","id","update/delete必填，正整数字符串","revision","update/delete必填，来自当前记录","data","create/update完整业务数据对象，按schema校验；update可先读取记录并合并修改","items","bulk操作使用1至50个单条操作参数对象，不可嵌套批量")));
        return result;
    }
    public JsonNode query(AgentCall call,String resource,JsonNode arguments){
        requireReadable(call.actor(),resource);var context=access.current(call.actor());
        if(resource.equals("forms")){
            AgentArguments.only(arguments,"pageNum","pageSize","search","orderBy","order");
            var page=forms.forms(AgentArguments.integer(arguments,"pageNum",1,1,100000),AgentArguments.integer(arguments,"pageSize",20,1,100),AgentArguments.text(arguments,"search",100,false),AgentArguments.text(arguments,"orderBy",50,false),AgentArguments.text(arguments,"order",4,false));
            var result=json.valueToTree(page);for(var row:result.path("records"))((ObjectNode)row).put("resource","forms:"+row.path("id").asText());return result;
        }
        AgentArguments.only(arguments,"id","pageNum","pageSize","filterField","filterValue","orderBy","order","fields");long form=formId(resource);
        Set<String> selected=fields(arguments,form);JsonNode result;
        if(arguments.hasNonNull("id")){context.require("lowcode:record:query");result=json.valueToTree(forms.record(form,AgentArguments.id(arguments,"id")));project(result,selected);}
        else {
            context.require("lowcode:record:list");result=json.valueToTree(forms.records(form,AgentArguments.integer(arguments,"pageNum",1,1,100000),AgentArguments.integer(arguments,"pageSize",20,1,100),AgentArguments.text(arguments,"filterField",50,false),AgentArguments.text(arguments,"filterValue",5000,false),AgentArguments.text(arguments,"orderBy",60,false),AgentArguments.text(arguments,"order",4,false)));
            result.path("records").forEach(row->project(row,selected));
        }return result;
    }
    @Transactional
    public JsonNode mutate(AgentCall call,String resource,JsonNode arguments){
        requireReadable(call.actor(),resource);long form=formId(resource);String operation=AgentArguments.text(arguments,"operation",20,true);
        boolean bulk=Set.of("bulkCreate","bulkUpdate","bulkDelete").contains(operation);
        String action=bulk?operation.substring(4).toLowerCase(Locale.ROOT):operation;
        access.current(call.actor()).require(permission(action));
        if(!bulk)return change(call,form,action,arguments);
        AgentArguments.only(arguments,"operation","items");var items=arguments.path("items");
        if(!items.isArray()||items.isEmpty()||items.size()>50)throw AgentArguments.invalid("批量操作的items须包含1至50项。");
        var result=json.createObjectNode();var rows=result.putArray("records");
        for(var item:items){call.check();rows.add(change(call,form,action,item));}return result.put("count",rows.size());
    }
    private JsonNode change(AgentCall call,long form,String action,JsonNode input){
        call.check();
        AgentArguments.object(input);if(input.hasNonNull("operation")&&!action.equals(AgentArguments.text(input,"operation",20,true)))throw AgentArguments.invalid("批量项不能改变顶层操作类型。");
        if(action.equals("delete")){
            AgentArguments.only(input,"operation","id","revision");long id=AgentArguments.id(input,"id");forms.removeRecord(form,id,AgentArguments.revision(input,"revision"));return json.createObjectNode().put("id",Long.toString(id)).put("deleted",true);
        }
        AgentArguments.only(input,"operation","id","revision","schemaVersion","data");
        AgentArguments.object(input.get("data"));int schemaVersion=AgentArguments.integer(input,"schemaVersion",-1,1,Integer.MAX_VALUE);if(schemaVersion<1)throw AgentArguments.invalid("请提供当前schemaVersion。");
        if(action.equals("create")){
            if(input.hasNonNull("id")||input.hasNonNull("revision"))throw AgentArguments.invalid("新增记录不填写id或revision。");
            return json.valueToTree(forms.addRecord(form,new LowcodeService.Submission(schemaVersion,null,input.get("data"))));
        }
        if(!action.equals("update"))throw AgentArguments.invalid("操作未定义。");
        return json.valueToTree(forms.updateRecord(form,AgentArguments.id(input,"id"),new LowcodeService.Submission(schemaVersion,AgentArguments.revision(input,"revision"),input.get("data"))));
    }
    private Set<String> fields(JsonNode input,long form){
        if(!input.hasNonNull("fields"))return null;var selected=input.path("fields");if(!selected.isArray()||selected.isEmpty()||selected.size()>100)throw AgentArguments.invalid("fields须为1至100个字段标识。");
        var schema=(LowcodeSchema.Schema)forms.runtime(form).get("schema");var available=new HashSet<String>();schema.fields().forEach(f->available.add(f.fieldKey()));var result=new LinkedHashSet<String>();
        for(var field:selected){if(!field.isTextual()||!available.contains(field.asText())||!result.add(field.asText()))throw AgentArguments.invalid("fields包含未知或重复字段。");}return result;
    }
    private void project(JsonNode row,Set<String> fields){if(fields!=null&&row.path("data").isObject())((ObjectNode)row.path("data")).retain(fields);}
    private static long formId(String resource){if(!resource.startsWith("forms:"))throw AgentArguments.invalid("请选择forms:<id>业务资源。");return AgentArguments.id(resource.substring(6));}
    private static String permission(String action){return switch(action){case "create"->"lowcode:record:add";case "update"->"lowcode:record:edit";case "delete"->"lowcode:record:remove";default->throw AgentArguments.invalid("操作未定义。");};}
}
