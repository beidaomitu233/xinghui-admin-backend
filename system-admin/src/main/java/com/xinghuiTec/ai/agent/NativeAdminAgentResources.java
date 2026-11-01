package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.controller.*;
import com.xinghuiTec.domain.dto.*;
import com.xinghuiTec.quartz.controller.SysJobController;
import com.xinghuiTec.quartz.domain.SysJob;
import com.xinghuiTec.service.DictionaryService;
import jakarta.validation.Validator;
import org.springframework.context.annotation.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit business adapters reuse the same proxied controllers/services and DTO validation as native pages.
 * No arbitrary URL, Java method, SQL, table, permission or identity is supplied by the model. */
@Configuration(proxyBeanMethods=false)
public class NativeAdminAgentResources {
    record Query(Class<?> type,Function<JsonNode,Object> read,Map<String,Object> description,Function<JsonNode,Object> snapshot) {
        Query(Class<?> type,Function<JsonNode,Object> read,Map<String,Object> description){this(type,read,description,null);}
    }
    record Mutation(String permission,Class<?> type,Function<JsonNode,Object> execute,Function<JsonNode,JsonNode> prepare) {
        Mutation(String permission,Class<?> type,Function<JsonNode,Object> execute){this(permission,type,execute,null);}
    }
    @Bean AgentResourceProvider userAgentResources(SysUserController users,AgentAccess access,ObjectMapper json,Validator validator,JdbcTemplate locks){
        Function<JsonNode,JsonNode> updateData=a->{
            var detail=users.getUser(id(a)).getData();if(detail==null)throw AgentArguments.invalid("用户不存在或无访问权限");
            var current=json.valueToTree(detail.get("user"));var merged=json.createObjectNode();
            for(String key:List.of("username","nickname","email","mobile","avatar","status"))if(current.has(key))merged.set(key,current.get(key));
            merged.set("roleIds",json.valueToTree(detail.get("roleIds")));data(a).fields().forEachRemaining(e->merged.set(e.getKey(),e.getValue()));return merged;
        };
        return new NativeResource("users","用户管理","system:user",false,access,json,validator,locks,
            new Query(SysUserQueryDTO.class,a->a.hasNonNull("id")?users.getUser(id(a)):users.getUserList(dto(json,validator,a,SysUserQueryDTO.class)),Map.of("id","可选；读取单个用户，否则按分页条件查询")),
            Map.of("create",new Mutation("system:user:add",SysUserAddDTO.class,a->users.addUser(dto(json,validator,data(a),SysUserAddDTO.class))),
                   "update",new Mutation("system:user:edit",SysUserAddDTO.class,a->{var d=dto(json,validator,updateData.apply(a),SysUserAddDTO.class);d.setUserId(id(a));return users.updateUser(d);},updateData),
                   "delete",new Mutation("system:user:remove",null,a->users.deleteUser(id(a)))));
    }
    @Bean AgentResourceProvider roleAgentResources(SysRoleController roles,AgentAccess access,ObjectMapper json,Validator validator,JdbcTemplate locks){
        return new NativeResource("roles","角色管理","system:role",false,access,json,validator,locks,
            new Query(SysRoleQueryDTO.class,a->a.hasNonNull("id")?roles.getRoleById(id(a)):roles.getRoleList(dto(json,validator,a,SysRoleQueryDTO.class)),Map.of("id","可选；读取单个角色")),
            Map.of("create",new Mutation("system:role:add",SysRoleAddDTO.class,a->roles.addRole(dto(json,validator,data(a),SysRoleAddDTO.class))),
                   "update",new Mutation("system:role:edit",SysRoleAddDTO.class,a->{var d=dto(json,validator,data(a),SysRoleAddDTO.class);d.setRoleId(id(a));return roles.updateRole(d);}),
                   "delete",new Mutation("system:role:remove",null,a->roles.deleteRole(id(a)))));
    }
    @Bean AgentResourceProvider noticeAgentResources(SysNoticeController notices,AgentAccess access,ObjectMapper json,Validator validator,JdbcTemplate locks){
        return new NativeResource("notices","站内公告","system:notice",false,access,json,validator,locks,
            new Query(SysNoticeQueryDTO.class,a->a.hasNonNull("id")?notices.getNoticeById(id(a)):notices.getNoticeList(dto(json,validator,a,SysNoticeQueryDTO.class)),Map.of("id","可选；读取单个公告")),
            Map.of("create",new Mutation("system:notice:add",SysNoticeAddDTO.class,a->notices.addNotice(dto(json,validator,data(a),SysNoticeAddDTO.class))),
                   "update",new Mutation("system:notice:edit",SysNoticeAddDTO.class,a->{var d=dto(json,validator,data(a),SysNoticeAddDTO.class);d.setNoticeId(id(a));return notices.updateNotice(d);}),
                   "delete",new Mutation("system:notice:remove",null,a->notices.deleteNotice(id(a)))));
    }
    @Bean AgentResourceProvider dictionaryAgentResources(DictionaryAdminController dictionary,AgentAccess access,ObjectMapper json,Validator validator,JdbcTemplate locks){
        return new NativeResource("dictionary","字典管理","system:dict",true,access,json,validator,locks,
            new Query(null,a->a.hasNonNull("id")?dictionary.items(id(a)):dictionary.types(),Map.of("id","可选；指定字典类型ID查询条目，省略列出类型"),a->{
                if("updateItem".equals(a.path("operation").asText())){
                    var item=dictionary.items(AgentArguments.id(a,"typeId")).getData().stream().filter(v->v.id()==id(a)).findFirst().orElseThrow(()->AgentArguments.invalid("条目不属于指定字典类型"));
                    return com.xinghuiTec.utils.Result.ok(item);
                }
                var type=dictionary.types().getData().stream().filter(v->v.id()==id(a)).findFirst().orElseThrow(()->AgentArguments.invalid("字典类型不存在"));
                return com.xinghuiTec.utils.Result.ok(type);
            }),
            Map.of("createType",new Mutation("system:dict:type:add",DictionaryService.TypeCreate.class,a->dictionary.addType(dto(json,validator,data(a),DictionaryService.TypeCreate.class))),
                "updateType",new Mutation("system:dict:type:edit",DictionaryService.TypeEdit.class,a->dictionary.editType(id(a),dto(json,validator,data(a),DictionaryService.TypeEdit.class))),
                "createItem",new Mutation("system:dict:item:add",DictionaryService.ItemCreate.class,a->dictionary.addItem(id(a),dto(json,validator,data(a),DictionaryService.ItemCreate.class))),
                "updateItem",new Mutation("system:dict:item:edit",DictionaryService.ItemEdit.class,a->dictionary.editItem(id(a),dto(json,validator,data(a),DictionaryService.ItemEdit.class)))));
    }
    @Bean AgentResourceProvider jobAgentResources(SysJobController jobs,AgentAccess access,ObjectMapper json,Validator validator,JdbcTemplate locks){
        return new NativeResource("jobs","定时任务","monitor:job",true,access,json,validator,locks,
            new Query(null,a->{if(a.hasNonNull("id"))return jobs.getInfo(id(a));AgentArguments.only(a,"pageNum","pageSize");return jobs.list(new SysJob(),AgentArguments.integer(a,"pageNum",1,1,100000),AgentArguments.integer(a,"pageSize",20,1,100),"createTime","desc");},Map.of("id","可选；指定任务ID","pageNum","默认1","pageSize","1至100")),
            Map.of("changeStatus",new Mutation("monitor:job:changeStatus",SysJobController.StatusChangeRequest.class,a->jobs.changeStatus(id(a),dto(json,validator,data(a),SysJobController.StatusChangeRequest.class))),
                "run",new Mutation("monitor:job:run",null,a->jobs.run(id(a)))));
    }
    static final class NativeResource implements AgentResourceProvider {
        private final String namespace,name,permission;private final boolean platform;private final AgentAccess access;private final ObjectMapper json;private final Validator validator;private final JdbcTemplate locks;private final Query query;private final Map<String,Mutation> changes;
        NativeResource(String namespace,String name,String permission,boolean platform,AgentAccess access,ObjectMapper json,Validator validator,JdbcTemplate locks,Query query,Map<String,Mutation> changes){this.namespace=namespace;this.name=name;this.permission=permission;this.platform=platform;this.access=access;this.json=json;this.validator=validator;this.locks=locks;this.query=query;this.changes=changes;}
        public String namespace(){return namespace;}
        private boolean readable(AgentAccess.Context current){return (!platform||current.platform())&&(current.has(permission+":list")||current.has(permission+":query"));}
        public JsonNode catalog(AgentCall call){var result=json.createArrayNode();if(readable(access.current(call.actor())))result.addObject().put("resource",namespace).put("name",name).put("readOnly",changes.isEmpty());return result;}
        public void requireReadable(AgentActor actor,String resource){if(!namespace.equals(resource)||!readable(access.current(actor)))throw AgentAccess.denied();}
        public JsonNode describe(AgentCall call,String resource){requireReadable(call.actor(),resource);var current=access.current(call.actor());var result=json.createObjectNode().put("resource",resource).put("name",name);
            var operations=result.putArray("operations").add("query");var mutations=result.putObject("mutation");
            changes.forEach((operation,change)->{if(current.has(change.permission())){operations.add(operation);var description=mutations.putObject(operation);description.put("operation",operation).put("id","修改、删除、状态切换或执行时提供查询返回的目标ID字符串");description.set("data",fields(change.type()));}});
            if(namespace.equals("users")&&mutations.has("update"))((ObjectNode)mutations.get("update")).put("description","data只填写要修改的字段，未提供的字段保留原值；不要把mobileMasked/emailMasked写回手机号或邮箱，不需要读取原始密码。");
            if(namespace.equals("menus")&&mutations.has("update"))((ObjectNode)mutations.get("update")).put("description","data只填写要修改的原生菜单字段，未提供的字段保留原值。目标menuId只在顶层id中提供。");
            if(namespace.equals("form-definitions"))result.put("design","先读取当前表单的schema和revision。update需保留已有字段和formKey，并提供完整name/formKey/status/revision/schema；publish和delete的data只含revision。字段类型和发布兼容性由原表单服务校验，保存草稿不会自动发布。");
            if(namespace.equals("dictionary")){
                if(mutations.has("updateItem"))((ObjectNode)mutations.get("updateItem")).put("id","条目ID，不是类型ID").put("typeId","必填；条目所属的字典类型ID");
                if(mutations.has("createItem"))((ObjectNode)mutations.get("createItem")).put("id","父级字典类型ID");
            }
            result.set("query",fields(query.type()));((ObjectNode)result.get("query")).setAll((ObjectNode)json.valueToTree(query.description()));
            return result;
        }
        public JsonNode query(AgentCall call,String resource,JsonNode args){requireReadable(call.actor(),resource);if(args.hasNonNull("id"))AgentArguments.only(args,"id");
            return result(query.read().apply(args));}
        private Mutation validate(AgentCall call,String resource,JsonNode args){requireReadable(call.actor(),resource);AgentArguments.only(args,"operation","id","typeId","data");
            var operation=AgentArguments.text(args,"operation",32,true);var change=changes.get(operation);if(change==null)throw AgentArguments.invalid("此操作未开放");access.current(call.actor()).require(change.permission());
            if(Set.of("create","createType").contains(operation)){if(args.hasNonNull("id"))throw AgentArguments.invalid("新增时不能指定已有记录ID");}else id(args);
            if(namespace.equals("dictionary")&&operation.equals("updateItem"))AgentArguments.id(args,"typeId");else if(args.has("typeId"))throw AgentArguments.invalid("此操作不接受typeId");
            if(change.type()!=null)dto(json,validator,change.prepare()==null?data(args):change.prepare().apply(args),change.type());else if(args.hasNonNull("data"))throw AgentArguments.invalid("此操作不接受data");
            return change;
        }
        public JsonNode changeSnapshot(AgentCall call,String resource,JsonNode args){
            validate(call,resource,args);if(!args.hasNonNull("id"))return null;
            if(TransactionSynchronizationManager.isActualTransactionActive())lockTarget(call,args);
            return result(query.snapshot()!=null?query.snapshot().apply(args):query.read().apply(json.createObjectNode().set("id",args.get("id"))));
        }
        private void lockTarget(AgentCall call,JsonNode args){
            // Fixed native row locks close the preview/confirmation race. No table or SQL is accepted from tool arguments.
            String sql=switch(namespace){
                case "users"->"SELECT user_id FROM sys_user WHERE user_id=? AND tenant_id=? FOR UPDATE";
                case "roles"->"SELECT role_id FROM sys_role WHERE role_id=? AND tenant_id=? FOR UPDATE";
                case "notices"->"SELECT notice_id FROM sys_notice WHERE notice_id=? AND tenant_id=? FOR UPDATE";
                case "jobs"->"SELECT job_id FROM sys_job WHERE job_id=? FOR UPDATE";
                case "dictionary"->"updateItem".equals(args.path("operation").asText())?"SELECT item_id FROM sys_dict_item WHERE item_id=? AND type_id=? FOR UPDATE":"SELECT type_id FROM sys_dict_type WHERE type_id=? FOR UPDATE";
                case "menus"->"SELECT menu_id FROM sys_menu WHERE menu_id=? AND is_deleted=0 FOR UPDATE";
                case "form-definitions"->"SELECT id FROM sys_lowcode_form WHERE id=? AND is_deleted=0 FOR UPDATE";
                default->throw new IllegalStateException("No native snapshot lock");
            };
            Object[] params=!platform&&!namespace.equals("menus")?new Object[]{id(args),call.actor().tenantId()}:namespace.equals("dictionary")&&"updateItem".equals(args.path("operation").asText())?new Object[]{id(args),AgentArguments.id(args,"typeId")}:new Object[]{id(args)};
            if(locks.queryForList(sql,params).size()!=1)throw AgentArguments.invalid("目标不存在或无访问权限");
        }
        public JsonNode mutate(AgentCall call,String resource,JsonNode args){var change=validate(call,resource,args);
            return result(change.execute().apply(args));}
        private JsonNode result(Object value){var envelope=json.valueToTree(value);if(envelope.path("code").asInt()!=200)throw new AgentProblem("BUSINESS_VALIDATION","业务校验未通过，请检查目标记录和参数");return safe(envelope.path("data"));}
        private ObjectNode fields(Class<?> type){var result=json.createObjectNode();if(type==null)return result;
            var descriptor=validator.getConstraintsForClass(type);
            for(var property:json.getDeserializationConfig().introspect(json.constructType(type)).findProperties()){
                if(Set.of("userId","roleId","noticeId","menuId").contains(property.getName()))continue;
                var field=result.putObject(property.getName());field.put("type",property.getPrimaryType().getRawClass().getSimpleName());var constraints=descriptor.getConstraintsForProperty(property.getName());
                if(constraints!=null){var rules=field.putArray("rules");constraints.getConstraintDescriptors().forEach(rule->{var r=rules.addObject().put("constraint",rule.getAnnotation().annotationType().getSimpleName());rule.getAttributes().forEach((key,value)->{if(Set.of("min","max","regexp","value").contains(key))r.put(key,String.valueOf(value));});});}
            }return result;
        }
        private JsonNode safe(JsonNode value){
            if(value.isObject()){var copy=json.createObjectNode();value.fields().forEachRemaining(e->{String key=e.getKey();if(key.toLowerCase(Locale.ROOT).matches(".*(password|secret|token|credential|salt).*"))return;
                if(key.equals("mobile")&&e.getValue().isTextual()){String mobile=e.getValue().asText();copy.put("mobileMasked",mobile.length()>=7?mobile.substring(0,3)+"****"+mobile.substring(mobile.length()-4):"***");}
                else if(key.equals("email")&&e.getValue().isTextual())copy.put("emailMasked",e.getValue().asText().replaceAll("(?<=^.).*(?=@)","***"));else copy.set(key,safe(e.getValue()));});return copy;}
            if(value.isArray()){var array=json.createArrayNode();value.forEach(v->array.add(safe(v)));return array;}return value.deepCopy();
        }
    }
    private static long id(JsonNode args){return AgentArguments.id(args,"id");}
    private static JsonNode data(JsonNode args){var data=args.get("data");AgentArguments.object(data);if(data.has("userId")||data.has("roleId")||data.has("noticeId")||data.has("menuId"))throw AgentArguments.invalid("目标ID只能在顶层id中填写");return data;}
    private static <T>T dto(ObjectMapper json,Validator validator,JsonNode input,Class<T> type){
        try{var value=json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(input);var errors=validator.validate(value);if(!errors.isEmpty())throw AgentArguments.invalid(errors.iterator().next().getMessage());return type.cast(value);}
        catch(AgentProblem safe){throw safe;}catch(Exception invalid){throw AgentArguments.invalid("请按资源定义填写字段和类型");}
    }
}
