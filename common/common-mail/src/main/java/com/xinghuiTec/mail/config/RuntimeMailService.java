package com.xinghuiTec.mail.config;

import cn.hutool.extra.mail.MailAccount;
import com.xinghuiTec.config.BusinessConfigSource;
import com.xinghuiTec.config.BusinessConfigSource.Snapshot;
import com.xinghuiTec.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Map;

/** Resolve each send against the current configuration, including an explicit disabled override. */
public class RuntimeMailService {
    private final ObjectProvider<BusinessConfigSource> source;
    private final ObjectProvider<MailAccount> deployment;
    public RuntimeMailService(ObjectProvider<BusinessConfigSource> source,ObjectProvider<MailAccount> deployment) {
        this.source=source;this.deployment=deployment;
    }
    public MailAccount current() {
        var provider=source.getIfAvailable();
        var snapshot=provider==null?null:provider.current("mail").orElse(null);
        if(snapshot!=null) {
            if(!snapshot.enabled()) throw new ServiceException("邮件服务已停用");
            return configuration(snapshot);
        }
        var account=deployment.getIfAvailable();
        if(account==null) throw new ServiceException("邮件服务尚未配置或已停用");
        return account;
    }
    public static void validateParameters(Map<String,String> p) {
        if(!java.util.Set.of("qq","163","enterprise","smtp").contains(p.get("provider")))
            throw new ServiceException("邮件服务商无效");
        String host=p.getOrDefault("host","");
        if(!host.isEmpty() && (!host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?") || host.contains("..")))
            throw new ServiceException("SMTP 主机格式无效");
        String port=p.getOrDefault("port","");
        if(!port.isEmpty() && (!port.matches("[0-9]{1,5}") || Integer.parseInt(port)<1 || Integer.parseInt(port)>65535))
            throw new ServiceException("SMTP 端口无效");
        if(!java.util.Set.of("SSL","STARTTLS").contains(p.get("security"))) throw new ServiceException("请选择 SSL 或 STARTTLS");
        for(String field:java.util.List.of("user","from")) {
            String value=p.getOrDefault(field,"");
            if(!value.isEmpty() && !value.matches("[^\\s<>@,;]{1,128}@[^\\s<>@,;]{1,128}\\.[^\\s<>@,;]{1,64}"))
                throw new ServiceException("请填写完整的邮件账号和发件地址");
        }
        if("qq".equals(p.get("provider")) && !("smtp.qq.com".equals(host) && "465".equals(port) && "SSL".equals(p.get("security")))
                || "163".equals(p.get("provider")) && !("smtp.163.com".equals(host) && "465".equals(port) && "SSL".equals(p.get("security"))))
            throw new ServiceException("服务商预设须使用对应的 SMTP 主机、465 端口与 SSL");
    }
    public static MailAccount configuration(Snapshot snapshot) {
        var p=snapshot.parameters();validateParameters(p);
        String pass=snapshot.secrets().get("password");
        if(java.util.List.of("host","port","user","from").stream().anyMatch(key->p.getOrDefault(key,"").isBlank()) || pass==null || pass.isBlank())
            throw new ServiceException("请补齐邮件服务商配置与授权码");
        var account=new MailAccount();
        account.setHost(p.get("host"));account.setPort(Integer.parseInt(p.get("port")));
        account.setAuth(true);account.setUser(p.get("user"));account.setFrom(p.get("from"));account.setPass(pass);
        boolean ssl="SSL".equals(p.get("security"));
        account.setSslEnable(ssl);account.setStarttlsEnable(!ssl);account.setSocketFactoryPort(account.getPort());
        account.setCustomProperty("mail.smtp.starttls.required",!ssl);
        account.setCustomProperty("mail.smtp.ssl.checkserveridentity",true);
        account.setSocketFactoryFallback(false);
        // Avoid unbounded worker blocking on an unreachable service.
        account.setTimeout(10000L);account.setConnectionTimeout(10000L);account.setWriteTimeout(10000L);
        return account;
    }
    public static void validate(Snapshot snapshot) {
        validateParameters(snapshot.parameters());
        if(snapshot.enabled()) configuration(snapshot);
    }
}
