package com.xinghuiTec.sms.core;

import com.xinghuiTec.config.BusinessConfigSource;
import com.xinghuiTec.config.BusinessConfigSource.Snapshot;
import com.xinghuiTec.exception.ServiceException;
import org.dromara.sms4j.api.SmsBlend;
import org.dromara.sms4j.api.entity.SmsResponse;
import org.dromara.sms4j.aliyun.config.AlibabaConfig;
import org.dromara.sms4j.tencent.config.TencentConfig;
import org.dromara.sms4j.provider.config.BaseConfig;
import org.dromara.sms4j.core.factory.SmsFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;

/** Actual sms4j supplier adaptation; no preview/mock send success. */
@Service
public class RuntimeSmsService {
    private final ObjectProvider<BusinessConfigSource> source;
    public RuntimeSmsService(ObjectProvider<BusinessConfigSource> source) { this.source=source; }
    public BaseConfig configuration(Snapshot snapshot) {
        var p=snapshot.parameters();
        BaseConfig config;
        if("alibaba".equals(p.get("provider"))) {
            var aliyun=new AlibabaConfig();aliyun.setRegionId(p.getOrDefault("region","cn-hangzhou"));config=aliyun;
        } else if("tencent".equals(p.get("provider"))) {
            var tencent=new TencentConfig();tencent.setSdkAppId(p.getOrDefault("sdkAppId",""));config=tencent;
        } else throw new ServiceException("请选择支持的短信供应商");
        config.setConfigId("business-sms-"+snapshot.revision());
        config.setSignature(p.getOrDefault("signature",""));config.setTemplateId(p.getOrDefault("templateId",""));
        config.setAccessKeyId(snapshot.secrets().get("accessKeyId"));config.setAccessKeySecret(snapshot.secrets().get("accessKeySecret"));
        config.setMaxRetries(0);
        if(config.getSignature().isBlank() || config.getTemplateId().isBlank()
                || config.getAccessKeyId()==null || config.getAccessKeyId().isBlank() || config.getAccessKeySecret()==null || config.getAccessKeySecret().isBlank()
                || config instanceof TencentConfig && config.getSdkAppId().isBlank()) throw new ServiceException("请补齐短信供应商配置与密钥");
        return config;
    }
    public synchronized void validate(Snapshot snapshot) {
        if(!snapshot.enabled()) return;
        try { configuration(snapshot); }
        catch(ServiceException invalid) { throw invalid; }
        catch(RuntimeException invalid) { throw new ServiceException("短信配置无效"); }
    }
    public SmsResponse send(String phone,LinkedHashMap<String,String> variables) {
        var provider=source.getIfAvailable();
        var snapshot=provider==null?null:provider.current("sms").orElse(null);
        if(snapshot==null || !snapshot.enabled()) throw new ServiceException("短信服务已停用");
        if(phone==null || !phone.matches("[0-9+]{6,20}")) throw new ServiceException("手机号格式无效");
        try {
            SmsBlend blend;
            synchronized(this) {
                var config=configuration(snapshot);
                blend=SmsFactory.getAll().stream().filter(item->config.getConfigId().equals(item.getConfigId())).findFirst().orElse(null);
                if(blend==null) { SmsFactory.createSmsBlend(config);blend=SmsFactory.getSmsBlend(config.getConfigId()); }
                if(blend==null) throw new ServiceException("短信供应商实例不可用");
            }
            return blend.sendMessage(phone,variables);
        } catch(ServiceException invalid) { throw invalid; }
        catch(RuntimeException unavailable) { throw new ServiceException("短信供应商请求失败"); }
    }
}
