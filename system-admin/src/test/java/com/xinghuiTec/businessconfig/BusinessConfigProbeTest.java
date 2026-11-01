package com.xinghuiTec.businessconfig;

import com.xinghuiTec.config.BusinessConfigSource;
import com.xinghuiTec.mail.config.RuntimeMailService;
import com.xinghuiTec.sms.core.RuntimeSmsService;
import org.dromara.sms4j.api.entity.SmsResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessConfigProbeTest {
    BusinessConfigService configs=mock(BusinessConfigService.class);
    RuntimeSmsService sms=mock(RuntimeSmsService.class);
    RuntimeMailService mail=mock(RuntimeMailService.class);
    BusinessConfigProbe probes=new BusinessConfigProbe(configs,sms,mail);
    BusinessConfigService.View view(boolean enabled){return new BusinessConfigService.View("sms",enabled,Map.of("provider","alibaba"),Map.of(),3,null,"DATABASE","ACTIVE",true);}
    BusinessConfigProbe.Request request(int revision){return new BusinessConfigProbe.Request(revision,"13800000000",null,null,Map.of("code","123456"));}
    @Test void rejectsDisabledOrStaleConfigurationBeforeContactingSupplier(){
        when(configs.get("sms")).thenReturn(view(false));
        assertThatThrownBy(()->probes.test("sms",request(3))).hasMessageContaining("启用");
        when(configs.get("sms")).thenReturn(view(true));
        assertThatThrownBy(()->probes.test("sms",request(2))).hasMessageContaining("配置已变化");
        verifyNoInteractions(sms,mail);
    }
    @Test void sendsExactReviewedSnapshotAndOnlyAcknowledgesActualSupplierAcceptance(){
        var snapshot=new BusinessConfigSource.Snapshot("sms",true,3,Map.of(),Map.of("accessKeySecret","private-unit-key"));
        when(configs.get("sms")).thenReturn(view(true));when(configs.current("sms")).thenReturn(Optional.of(snapshot));
        var response=mock(SmsResponse.class);when(response.isSuccess()).thenReturn(true);
        when(sms.send(eq(snapshot),eq("13800000000"),any())).thenReturn(response);
        var receipt=probes.test("sms",request(3));
        assertThat(receipt.status()).isEqualTo("ACCEPTED");assertThat(receipt.revision()).isEqualTo(3);
        assertThat(receipt.toString()).doesNotContain("13800000000","private-unit-key","123456");
        when(response.isSuccess()).thenReturn(false);
        assertThatThrownBy(()->probes.test("sms",request(3))).hasMessageContaining("未受理");
    }
    @Test void rejectsMultipleMailRecipientsAndHeaderInjection(){
        when(configs.get("mail")).thenReturn(new BusinessConfigService.View("mail",true,Map.of("provider","smtp"),Map.of(),3,null,"DEPLOYMENT","ACTIVE",true));
        when(configs.current("mail")).thenReturn(Optional.empty());
        assertThatThrownBy(()->probes.test("mail",new BusinessConfigProbe.Request(3,"a@example.invalid,b@example.invalid","测试","内容",null))).hasMessageContaining("单个");
        assertThatThrownBy(()->probes.test("mail",new BusinessConfigProbe.Request(3,"a@example.invalid","主题\r\nBcc:x@example.invalid","内容",null))).hasMessageContaining("主题");
        verifyNoInteractions(sms,mail);
    }
    @Test void mailAcceptanceRequiresTheRealSenderReceiptAndDoesNotExposeTransportErrors(){
        when(configs.get("mail")).thenReturn(new BusinessConfigService.View("mail",true,Map.of("provider","smtp"),Map.of(),3,null,"DEPLOYMENT","ACTIVE",true));
        when(configs.current("mail")).thenReturn(Optional.empty());
        var account=new cn.hutool.extra.mail.MailAccount();when(mail.current()).thenReturn(account);
        var request=new BusinessConfigProbe.Request(3,"qa@example.invalid","测试","仅测试内容",null);
        when(mail.sendText(account,"qa@example.invalid","测试","仅测试内容")).thenReturn("message-accepted");
        assertThat(probes.test("mail",request).status()).isEqualTo("ACCEPTED");
        verify(mail).sendText(account,"qa@example.invalid","测试","仅测试内容");
        when(mail.sendText(account,"qa@example.invalid","测试","仅测试内容")).thenThrow(new IllegalStateException("private-smtp-password"));
        assertThatThrownBy(()->probes.test("mail",request)).hasMessageContaining("未发送成功").hasMessageNotContaining("private-smtp-password");
    }
}
