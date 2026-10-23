package com.xinghuiTec.businessconfig;

import cn.hutool.extra.mail.MailAccount;
import com.xinghuiTec.config.BusinessConfigSource;
import com.xinghuiTec.config.BusinessConfigSource.Snapshot;
import com.xinghuiTec.mail.config.RuntimeMailService;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeMailServiceTest {
    @Test void disabledDatabaseConfigurationRejectsSendingEvenWithEnabledDeployment() {
        ObjectProvider<BusinessConfigSource> sources=mock(ObjectProvider.class);ObjectProvider<MailAccount> deployment=mock(ObjectProvider.class);
        var source=mock(BusinessConfigSource.class);when(sources.getIfAvailable()).thenReturn(source);
        when(source.current("mail")).thenReturn(Optional.of(new Snapshot("mail",false,1,Map.of(),Map.of())));
        assertThrows(ServiceException.class,()->new RuntimeMailService(sources,deployment).current());
        verifyNoInteractions(deployment);
    }
    @Test void everySendResolvesLatestVendorAccountAndCredentialsWithoutRestart() {
        ObjectProvider<BusinessConfigSource> sources=mock(ObjectProvider.class);ObjectProvider<MailAccount> deployment=mock(ObjectProvider.class);
        var source=mock(BusinessConfigSource.class);when(sources.getIfAvailable()).thenReturn(source);
        var qq=new Snapshot("mail",true,1,Map.of("provider","qq","host","smtp.qq.com","port","465","security","SSL","user","fixture@qq.com","from","fixture@qq.com"),Map.of("password","qq-fixture"));
        var netease=new Snapshot("mail",true,2,Map.of("provider","163","host","smtp.163.com","port","465","security","SSL","user","fixture@163.com","from","fixture@163.com"),Map.of("password","163-fixture"));
        when(source.current("mail")).thenReturn(Optional.of(qq),Optional.of(netease));
        var service=new RuntimeMailService(sources,deployment);var first=service.current();var second=service.current();
        assertEquals("smtp.qq.com",first.getHost());assertEquals("qq-fixture",first.getPass());
        assertEquals("smtp.163.com",second.getHost());assertEquals("163-fixture",second.getPass());
        verifyNoInteractions(deployment);
    }
}
