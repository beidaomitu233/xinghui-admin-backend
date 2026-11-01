package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.controller.*;
import com.xinghuiTec.domain.dto.*;
import com.xinghuiTec.lowcode.LowcodeFormController;
import com.xinghuiTec.lowcode.LowcodeService;
import com.xinghuiTec.quartz.controller.SysJobLogController;
import com.xinghuiTec.utils.Result;
import jakarta.validation.Validator;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.function.Function;

/** Explicit read adapters. Native controllers retain owner/tenant/platform checks; no arbitrary endpoint dispatch. */
@Configuration(proxyBeanMethods=false)
public class NativeReadAgentResources {
    private final AgentAccess access;private final ObjectMapper json;private final Validator validator;private final JdbcTemplate jdbc;
    public NativeReadAgentResources(AgentAccess access,ObjectMapper json,Validator validator,JdbcTemplate jdbc){this.access=access;this.json=json;this.validator=validator;this.jdbc=jdbc;}

    @Bean AgentResourceProvider menuAgentResources(SysMenuController menus){
        Function<JsonNode,JsonNode> merged=a->{
            AgentArguments.object(a.get("data"));if(a.path("data").has("menuId"))throw AgentArguments.invalid("目标ID只能在顶层id中填写");
            var current=menus.getMenuById(id(a));if(current.getData()==null)throw AgentArguments.invalid("菜单不存在或无访问权限");
            var value=json.createObjectNode();var original=json.valueToTree(current.getData());
            for(String field:List.of("menuName","parentId","orderNum","path","component","isFrame","menuType","visible","status","perms","icon"))if(original.has(field))value.set(field,original.get(field));
            a.path("data").fields().forEachRemaining(e->value.set(e.getKey(),e.getValue()));return value;
        };
        return new NativeAdminAgentResources.NativeResource("menus","系统菜单与入口","system:menu",false,access,json,validator,jdbc,
            new NativeAdminAgentResources.Query(SysMenuQueryDTO.class,a->a.hasNonNull("id")?menus.getMenuById(id(a)):menus.getMenuList(dto(a,SysMenuQueryDTO.class)),Map.of("id","可选；读取单个菜单。列表可按menuName/status等原生字段查询")),
            Map.of("create",new NativeAdminAgentResources.Mutation("system:menu:add",SysMenuAddDTO.class,a->menus.addMenu(dto(a.path("data"),SysMenuAddDTO.class))),
                "update",new NativeAdminAgentResources.Mutation("system:menu:edit",SysMenuAddDTO.class,a->{var value=dto(merged.apply(a),SysMenuAddDTO.class);value.setMenuId(id(a));return menus.updateMenu(value);},merged),
                "delete",new NativeAdminAgentResources.Mutation("system:menu:remove",null,a->menus.deleteMenu(id(a)))));
    }
    @Bean AgentResourceProvider fileAgentResources(SysOssController files){
        return view("files","当前账号的文件元数据","system:oss",false,null,a->{
            AgentArguments.only(a,"id","pageNum","pageSize","originalName","fileSuffix");
            var result=a.hasNonNull("id")?files.getInfo(id(a)):files.getList(page(a),size(a),text(a,"originalName",200),text(a,"fileSuffix",20),"createTime","desc");
            return project(result,Set.of("ossId","originalName","size","fileSuffix","createTime","createBy","tenantId"));
        },Map.of("id","可选；仅限原文件服务允许当前用户读取的文件","pageNum","默认1","pageSize","1至100，默认20","originalName","可选，文件名搜索","fileSuffix","可选，扩展名。只提供元数据，不返回下载凭证或对象URL"));
    }
    @Bean AgentResourceProvider operationLogAgentResources(SysOperLogController logs){
        return view("operation-logs","操作日志摘要","monitor:operlog",true,SysOperLogQueryDTO.class,
            a->project(logs.getOperLogList(dto(a,SysOperLogQueryDTO.class)),Set.of("operId","title","businessType","operName","status","operTime","costTime")),
            Map.of("description","按原生查询条件分页检索。只返回审计摘要，不返回请求参数、响应正文或异常堆栈"));
    }
    @Bean AgentResourceProvider fireLogAgentResources(SysJobLogController logs){
        return view("job-logs","任务调度执行记录","monitor:jobLog",true,null,a->{
            AgentArguments.only(a,"id","pageNum","pageSize","jobId","jobName","status","triggerType");
            if(a.hasNonNull("id"))return logs.detail(id(a));
            return logs.list(page(a),size(a),a.hasNonNull("jobId")?AgentArguments.id(a,"jobId"):null,text(a,"jobName",64),text(a,"status",30),text(a,"triggerType",30),"fireId","desc");
        },Map.of("id","可选；单条执行ID，否则分页查询","pageNum","默认1","pageSize","1至100，默认20","jobId","可选，任务ID","jobName","可选，任务名称","status","可选，执行状态","triggerType","可选，触发类型"));
    }
    @Bean AgentResourceProvider formDefinitionAgentResources(LowcodeFormController forms){
        return new NativeAdminAgentResources.NativeResource("form-definitions","业务表单定义与发布状态","lowcode:form",true,access,json,validator,jdbc,new NativeAdminAgentResources.Query(null,a->{
            AgentArguments.only(a,"id","pageNum","pageSize","name");
            return a.hasNonNull("id")?forms.get(id(a)):forms.list(page(a),size(a),text(a,"name",128),"createTime","desc");
        },Map.of("id","可选；表单定义ID。查询实际业务记录请使用forms Skill","pageNum","默认1","pageSize","1至100，默认20","name","可选，表单名称")),
            Map.of("create",new NativeAdminAgentResources.Mutation("lowcode:form:add",LowcodeService.Draft.class,a->forms.create(dto(a.path("data"),LowcodeService.Draft.class))),
                "update",new NativeAdminAgentResources.Mutation("lowcode:form:edit",LowcodeService.Draft.class,a->forms.update(id(a),dto(a.path("data"),LowcodeService.Draft.class))),
                "publish",new NativeAdminAgentResources.Mutation("lowcode:form:publish",LowcodeService.VersionRequest.class,a->forms.publish(id(a),dto(a.path("data"),LowcodeService.VersionRequest.class))),
                "delete",new NativeAdminAgentResources.Mutation("lowcode:form:remove",LowcodeService.VersionRequest.class,a->forms.remove(id(a),dto(a.path("data"),LowcodeService.VersionRequest.class).revision()))));
    }
    @Bean AgentResourceProvider tenantAgentResources(SysTenantController tenants){
        return view("tenants","租户状态与容量","system:tenant",true,null,a->{
            AgentArguments.only(a,"id","pageNum","pageSize");
            return project(a.hasNonNull("id")?tenants.getInfo(id(a)):tenants.list(page(a),size(a)),Set.of("id","tenantId","companyName","packageId","expireTime","accountCount","status","createTime","updateTime"));
        },Map.of("id","可选；仅平台身份可查询租户","pageNum","默认1","pageSize","1至100，默认20"));
    }
    @Bean AgentResourceProvider tenantPackageAgentResources(SysTenantPackageController packages){
        return view("tenant-packages","租户套餐","system:tenantPackage",true,null,a->{
            AgentArguments.only(a,"id","pageNum","pageSize");
            return a.hasNonNull("id")?packages.getInfo(id(a)):packages.list(page(a),size(a));
        },Map.of("id","可选；仅平台身份可查询套餐","pageNum","默认1","pageSize","1至100，默认20"));
    }
    private AgentResourceProvider view(String namespace,String name,String permission,boolean platform,Class<?> type,Function<JsonNode,Object> read,Map<String,Object> description){
        return new NativeAdminAgentResources.NativeResource(namespace,name,permission,platform,access,json,validator,jdbc,new NativeAdminAgentResources.Query(type,read,description),Map.of());
    }
    private <T>T dto(JsonNode args,Class<T> type){
        try{T value=json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(args);if(!validator.validate(value).isEmpty())throw AgentArguments.invalid("请按资源定义填写有效的查询条件");return value;}
        catch(AgentProblem safe){throw safe;}catch(Exception invalid){throw AgentArguments.invalid("查询条件包含不支持的字段或类型");}
    }
    private Object project(Result<?> result,Set<String> fields){
        JsonNode envelope=json.valueToTree(result);if(envelope.path("code").asInt()!=200)return result;
        return Result.ok(projectData(envelope.path("data"),fields));
    }
    private JsonNode projectData(JsonNode data,Set<String> fields){
        if(data.isArray()){var values=json.createArrayNode();data.forEach(v->values.add(projectData(v,fields)));return values;}
        if(!data.isObject())return data;
        ObjectNode copy=json.createObjectNode();
        if(data.has("records")){for(String key:List.of("total","current","size","pages","pageNum","pageSize"))if(data.has(key))copy.set(key,data.get(key));copy.set("records",projectData(data.get("records"),fields));}
        else fields.forEach(key->{if(data.has(key))copy.set(key,data.get(key));});return copy;
    }
    private static int page(JsonNode a){return AgentArguments.integer(a,"pageNum",1,1,10000);}
    private static int size(JsonNode a){return AgentArguments.integer(a,"pageSize",20,1,100);}
    private static long id(JsonNode a){return AgentArguments.id(a,"id");}
    private static String text(JsonNode a,String key,int max){return AgentArguments.text(a,key,max,false);}
}
