package com.xinghuiTec.ai.hub;

import com.xinghuiTec.agent.api.AgentCall;
import com.xinghuiTec.aop.BusinessLogDetails;
import com.xinghuiTec.domain.entity.SysOperLog;
import com.xinghuiTec.service.SysOperLogService;
import com.xinghuiTec.utils.SecurityUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Publishes confirmed Agent business actions to the existing operation log after transaction commit. */
@Component
@lombok.extern.slf4j.Slf4j
public class AgentOperationLogs {
    private final SysOperLogService logs;
    public AgentOperationLogs(SysOperLogService logs){this.logs=logs;}
    public void record(AgentCall call,String resource,String operation,String status,Set<String> ids,long duration,String error){
        var log=new SysOperLog();log.setOperTime(new Date());log.setTitle("AI 中台业务变更");
        log.setBusinessType(switch(operation){case "create","bulkCreate","createType","createItem"->1;case "delete","bulkDelete"->3;default->2;});
        log.setMethod("AgentChanges.confirm_execute");log.setRequestMethod("POST");log.setOperUrl("[omitted]");
        log.setOperName(call.actor().userId());
        try{var user=SecurityUtils.getUser();if(user!=null)log.setOperName(user.getUsername());}catch(RuntimeException ignored){}
        log.setStatus("EXECUTED".equals(status)?0:1);log.setCostTime(duration);
        var parts=resource.split(":",2);
        String name=Map.of("forms","业务表单","users","用户","roles","角色","dictionary","字典","jobs","定时任务","notices","公告","business-config","业务配置","system-parameters","系统参数").getOrDefault(parts[0],parts[0]);
        String target=name+(parts.length>1?" #"+parts[1]:"")+(ids.isEmpty()?"":" · 记录 "+String.join(", ",ids));
        log.setOperParam(BusinessLogDetails.encode("AI 中台",target));log.setJsonResult(status);
        if(error!=null)log.setErrorMsg(error);
        Runnable publish=()->{try{logs.saveOperLog(log);}catch(RuntimeException unavailable){AgentOperationLogs.log.warn("Business log dispatch failed: {}",unavailable.getClass().getSimpleName());}};
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){publish.run();}});
        else publish.run();
    }
}
