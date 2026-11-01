package com.xinghuiTec.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.domain.entity.SysOperLog;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/** Useful business metadata from an explicit allowlist; never serializes a request, credential or response body. */
public final class BusinessLogDetails {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> IDS=Set.of("id","ids","userId","roleId","menuId","configId","noticeId","jobId","formId","recordId","ossId","typeId","itemId");
    private static final Map<Integer,String> ACTIONS=Map.of(0,"操作",1,"新增",2,"修改",3,"删除",4,"授权",5,"导出",6,"导入",7,"强退",8,"生成",9,"清空");
    private BusinessLogDetails(){}
    public static String metadata(Object[] arguments,HttpServletRequest request){
        var targets=new LinkedHashSet<String>();
        if(request!=null&&request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?,?> variables)
            variables.forEach((key,value)->{if(IDS.contains(String.valueOf(key)))id(targets,String.valueOf(key),value);else if("section".equals(key)&&Set.of("wechat","wechatMp","sms","mail","oss").contains(value))targets.add("配置:"+value);});
        if(request!=null)for(String key:IDS){var values=request.getParameterValues(key);if(values!=null)for(String value:values)id(targets,key,value);}
        if(arguments!=null)for(Object argument:arguments){
            if(argument==null)continue;
            if(argument instanceof Collection<?> values){values.forEach(value->id(targets,"id",value));continue;}
            var bean=new BeanWrapperImpl(argument);
            for(String key:IDS)if(bean.isReadableProperty(key)){
                Object value=bean.getPropertyValue(key);
                if(value instanceof Collection<?> values)values.forEach(item->id(targets,key,item));else id(targets,key,value);
            }
        }
        return targets.isEmpty()?"[omitted]":encode("管理接口",String.join(" · ",targets));
    }
    private static void id(Set<String> targets,String key,Object value){
        if(targets.size()<12&&value!=null&&value.toString().matches("[1-9][0-9]{0,18}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))targets.add(key+":"+value);
    }
    public static String encode(String source,String target){
        try{return JSON.writeValueAsString(Map.of("businessLogVersion",1,"source",source,"target",target));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    public static void enrich(SysOperLog log){
        log.setBusinessAction(ACTIONS.getOrDefault(log.getBusinessType()==null?0:log.getBusinessType(),"操作")+" · "+Objects.toString(log.getTitle(),"业务数据"));
        log.setBusinessResult(Objects.equals(log.getStatus(),0)?"操作成功":"操作失败");
        log.setBusinessTarget(null);log.setOperationSource("管理接口");
        try{
            var details=JSON.readTree(log.getOperParam());
            if(details.path("businessLogVersion").asInt()==1){
                log.setBusinessTarget(details.path("target").asText(null));
                log.setOperationSource(details.path("source").asText("管理接口"));
            }
        }catch(Exception oldLog){/* Legacy logs retain their omission instead of inventing targets. */}
    }
}
