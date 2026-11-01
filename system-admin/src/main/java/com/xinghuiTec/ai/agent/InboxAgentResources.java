package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.controller.NotificationInboxController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;

/** The existing inbox's authenticated owner scope is retained; no recipient identity is accepted. */
@Component
public final class InboxAgentResources implements AgentResourceProvider {
    private final NotificationInboxController inbox;private final AgentAccess access;private final ObjectMapper json;private final JdbcTemplate jdbc;
    public InboxAgentResources(NotificationInboxController inbox,AgentAccess access,ObjectMapper json,JdbcTemplate jdbc){this.inbox=inbox;this.access=access;this.json=json;this.jdbc=jdbc;}
    public String namespace(){return "inbox";}
    public void requireReadable(AgentActor actor,String resource){access.current(actor);if(!namespace().equals(resource))throw AgentAccess.denied();}
    public JsonNode catalog(AgentCall call){requireReadable(call.actor(),namespace());var entries=json.createArrayNode();entries.addObject().put("resource",namespace()).put("name","我的站内通知").put("readOnly",false);return entries;}
    public JsonNode describe(AgentCall call,String resource){requireReadable(call.actor(),resource);return json.valueToTree(Map.of(
        "name","我的站内通知","resource",resource,"operations",java.util.List.of("query","markRead"),
        "query",Map.of("id","可选；查询当前账号的一条通知","pageNum","默认1","pageSize","1至100，默认20","unreadOnly","可选布尔值，只查未读"),
        "mutation",Map.of("markRead",Map.of("operation","markRead","id","查询返回的通知ID字符串。经页面确认后标记当前账号通知已读"))));}
    public JsonNode query(AgentCall call,String resource,JsonNode args){requireReadable(call.actor(),resource);
        if(args.hasNonNull("id")){AgentArguments.only(args,"id");return json.valueToTree(inbox.detail(AgentArguments.id(args,"id")).getData());}
        AgentArguments.only(args,"pageNum","pageSize","unreadOnly");
        if(args.has("unreadOnly")&&!args.path("unreadOnly").isBoolean())throw AgentArguments.invalid("unreadOnly须为布尔值");
        return json.valueToTree(inbox.page(AgentArguments.integer(args,"pageNum",1,1,10000),AgentArguments.integer(args,"pageSize",20,1,100),args.path("unreadOnly").asBoolean(false),"createTime","desc").getData());
    }
    private long target(AgentCall call,String resource,JsonNode args){requireReadable(call.actor(),resource);AgentArguments.only(args,"operation","id");
        if(!"markRead".equals(AgentArguments.text(args,"operation",20,true)))throw AgentArguments.invalid("此操作未开放");return AgentArguments.id(args,"id");}
    public JsonNode changeSnapshot(AgentCall call,String resource,JsonNode args){long id=target(call,resource,args);
        if(TransactionSynchronizationManager.isActualTransactionActive()&&jdbc.queryForList("SELECT notification_id FROM sys_notification_inbox WHERE notification_id=? AND tenant_id=? AND recipient_user_id=? FOR UPDATE",id,call.actor().tenantId(),call.actor().userId()).size()!=1)throw AgentAccess.denied();
        return json.valueToTree(inbox.detail(id).getData());
    }
    public JsonNode mutate(AgentCall call,String resource,JsonNode args){long id=target(call,resource,args);inbox.markRead(id);
        return json.createObjectNode().put("id",Long.toString(id)).put("read",true);
    }
}
