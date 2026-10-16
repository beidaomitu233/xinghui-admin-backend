package com.xinghuiTec.businessconfig;
import com.xinghuiTec.config.BusinessConfigSource;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.sms.core.RuntimeSmsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.dromara.sms4j.aliyun.config.AlibabaConfig;
import org.dromara.sms4j.tencent.config.TencentConfig;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RuntimeSmsServiceTest {
    @Test void adaptsBothActualSms4jSupplierTypes() {
        var service=new RuntimeSmsService(mock(ObjectProvider.class));
        var p=new HashMap<>(Map.of("provider","alibaba","signature","unit-sign","templateId","unit-template","region","cn-hangzhou","sdkAppId","123"));
        var snapshot=new BusinessConfigSource.Snapshot("sms",true,5,p,Map.of("accessKeyId","unit-id","accessKeySecret","unit-secret"));
        var alibaba=assertInstanceOf(AlibabaConfig.class,service.configuration(snapshot));
        assertEquals("cn-hangzhou",alibaba.getRegionId());assertEquals("unit-template",alibaba.getTemplateId());
        p.put("provider","tencent");
        var tencent=assertInstanceOf(TencentConfig.class,service.configuration(snapshot));
        assertEquals("123",tencent.getSdkAppId());assertEquals("business-sms-5",tencent.getConfigId());
    }
    @Test void disabledConfigurationRejectsBeforeAnyProviderSend() {
        ObjectProvider<BusinessConfigSource> provider=mock(ObjectProvider.class);
        var source=mock(BusinessConfigSource.class);when(provider.getIfAvailable()).thenReturn(source);
        when(source.current("sms")).thenReturn(Optional.of(new BusinessConfigSource.Snapshot("sms",false,1,Map.of(),Map.of())));
        assertThrows(ServiceException.class,()->new RuntimeSmsService(provider).send("13800000000",new LinkedHashMap<>()));
    }
}
