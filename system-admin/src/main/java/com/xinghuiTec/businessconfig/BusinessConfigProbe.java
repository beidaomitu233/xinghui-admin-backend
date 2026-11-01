package com.xinghuiTec.businessconfig;

import com.xinghuiTec.mail.config.RuntimeMailService;
import com.xinghuiTec.sms.core.RuntimeSmsService;
import org.springframework.stereotype.Service;
import java.util.*;

/** Explicit administrator test of a saved configuration, through the same supplier adapters as production. */
@Service
public class BusinessConfigProbe {
    public record Request(int revision,String recipient,String subject,String content,Map<String,String> variables) {
        @Override public String toString(){return "BusinessConfigProbe[REDACTED]";}
    }
    public record Receipt(String status,String message,String requestId,String provider,int revision,long elapsedMs) {}
    private final BusinessConfigService configs;
    private final RuntimeSmsService sms;
    private final RuntimeMailService mail;
    public BusinessConfigProbe(BusinessConfigService configs,RuntimeSmsService sms,RuntimeMailService mail){this.configs=configs;this.sms=sms;this.mail=mail;}
    public Receipt test(String section,Request request){
        if(!Set.of("sms","mail").contains(section)||request==null)throw failure(400,"不支持此配置测试");
        var view=configs.get(section);
        if(view.revision()!=request.revision())throw failure(409,"配置已变化，请重新读取后测试");
        if(!view.enabled())throw failure(400,"请先保存并启用服务配置");
        var snapshot=configs.current(section).orElse(null);
        if(snapshot!=null&&snapshot.revision()!=request.revision())throw failure(409,"配置已变化，请重新读取后测试");
        String recipient=request.recipient();
        if(recipient==null||recipient.length()>254)throw failure(400,"请填写测试接收地址");
        var variables=new LinkedHashMap<String,String>();
        if(section.equals("sms")){
            if(!recipient.matches("1[3-9][0-9]{9}"))throw failure(400,"请填写正确的测试手机号");
            if(request.variables()==null||request.variables().size()>16)throw failure(400,"请填写有效的模板参数");
            request.variables().forEach((key,value)->{
                if(key==null||!key.matches("[A-Za-z0-9_]{1,64}")||value==null||value.length()>1000)throw failure(400,"模板参数无效");
                variables.put(key,value);
            });
        }else{
            if(!recipient.matches("[^\\s<>@,;]{1,128}@[^\\s<>@,;]{1,128}\\.[^\\s<>@,;]{1,64}"))throw failure(400,"请填写单个有效的收件邮箱");
            if(request.subject()==null||request.subject().isBlank()||request.subject().length()>120||request.subject().matches("(?s).*[\\r\\n].*"))throw failure(400,"邮件主题无效");
            if(request.content()==null||request.content().isBlank()||request.content().length()>5000)throw failure(400,"测试邮件内容需为1至5000个字符");
        }
        long started=System.nanoTime();
        try{
            if(section.equals("sms")){
                var response=sms.send(snapshot,recipient,variables);
                if(response==null||!response.isSuccess())throw new IllegalStateException("Supplier rejected test");
            }else{
                var account=snapshot==null?mail.current():RuntimeMailService.configuration(snapshot);
                String id=mail.sendText(account,recipient,request.subject(),request.content());
                if(id==null||id.isBlank())throw new IllegalStateException("Missing mail receipt");
            }
        }catch(RuntimeException unavailable){throw failure(502,section.equals("sms")?"短信服务商未受理测试，请核对模板、参数与服务商配置":"邮件未发送成功，请核对授权码、SMTP 地址与网络连接");}
        return new Receipt("ACCEPTED",section.equals("sms")?"短信服务商已受理，请在测试手机确认收件":"邮件服务器已受理，请在收件箱确认收件",UUID.randomUUID().toString(),view.parameters().getOrDefault("provider",""),view.revision(),(System.nanoTime()-started)/1_000_000);
    }
    private static BusinessConfigService.Failure failure(int status,String message){return new BusinessConfigService.Failure(status,message);}
}
