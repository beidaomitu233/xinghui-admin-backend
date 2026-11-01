package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.controller.*;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.service.DictionaryService;
import com.xinghuiTec.utils.Result;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeAdminAgentResourcesTest {
    AgentAdapterFixture fixture;ValidatorFactory validation;JdbcTemplate jdbc;TransactionTemplate tx;
    NativeAdminAgentResources factory=new NativeAdminAgentResources();
    @BeforeEach void setup(){
        fixture=new AgentAdapterFixture();validation=Validation.buildDefaultValidatorFactory();
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @AfterEach void close(){validation.close();fixture.close();}
    AgentResourceRegistry registry(AgentResourceProvider provider){return new AgentResourceRegistry(List.of(provider),fixture.access,fixture.json);}

    @Test void dictionaryItemPreviewUsesTheItemAndParentTypeInsteadOfTreatingItemIdAsTypeId(){
        fixture.permissions.addAll(Set.of("system:dict:list","system:dict:item:edit"));
        var controller=mock(DictionaryAdminController.class);
        when(controller.items(77)).thenReturn(Result.ok(List.of(new DictionaryService.ItemView(9,77,"pending","待处理",10,true,"QA"))));
        when(controller.items(88)).thenReturn(Result.ok(List.of()));
        when(controller.editItem(eq(9L),any())).thenReturn(Result.ok());
        jdbc.execute("CREATE TABLE sys_dict_item(item_id BIGINT PRIMARY KEY,type_id BIGINT)");jdbc.update("INSERT INTO sys_dict_item VALUES(9,77)");
        var gateway=registry(factory.dictionaryAgentResources(controller,fixture.access,fixture.json,validation.getValidator(),jdbc));
        var args=fixture.json.createObjectNode().put("operation","updateItem").put("id","9").put("typeId","77");
        args.putObject("data").put("label","处理中").put("enabled",true);
        var call=fixture.call(fixture.platform);
        var before=tx.execute(s->gateway.changeSnapshot(call,"dictionary",args));
        assertThat(before.path("id").asLong()).isEqualTo(9);assertThat(before.path("label").asText()).isEqualTo("待处理");
        gateway.mutate(call,"tool-1","dictionary",args);verify(controller).editItem(eq(9L),any());
        args.put("typeId","88");assertThatThrownBy(()->gateway.changeSnapshot(call,"dictionary",args)).isInstanceOf(AgentProblem.class);
    }

    @Test void partialUserUpdatePreservesPrivateContactValuesInsideNativeServiceWithoutGivingThemToModel(){
        fixture.permissions.addAll(Set.of("system:user:list","system:user:query","system:user:edit"));
        var controller=mock(SysUserController.class);var user=new SysUser();user.setUserId(7L);user.setUsername("qa_user");user.setMobile("19912345678");user.setEmail("qa-person@example.invalid");user.setNickname("原昵称");user.setStatus(1);
        when(controller.getUser(7L)).thenReturn(Result.ok(Map.of("user",user,"roleIds",List.of("100"))));when(controller.updateUser(any())).thenReturn(Result.ok());
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,tenant_id VARCHAR(20))");jdbc.update("INSERT INTO sys_user VALUES(7,'000000')");
        var gateway=registry(factory.userAgentResources(controller,fixture.access,fixture.json,validation.getValidator(),jdbc));
        var args=fixture.json.createObjectNode().put("operation","update").put("id","7");args.putObject("data").put("nickname","新昵称");
        var call=fixture.call(fixture.platform);
        var before=tx.execute(s->gateway.changeSnapshot(call,"users",args));
        assertThat(before.toString()).doesNotContain("19912345678","qa-person@example.invalid");assertThat(before.path("user").path("mobileMasked").asText()).isEqualTo("199****5678");
        gateway.mutate(call,"tool-2","users",args);
        var captured=ArgumentCaptor.forClass(SysUserAddDTO.class);verify(controller).updateUser(captured.capture());
        assertThat(captured.getValue().getNickname()).isEqualTo("新昵称");assertThat(captured.getValue().getMobile()).isEqualTo("19912345678");assertThat(captured.getValue().getRoleIds()).containsExactly(100L);
        assertThat(captured.getValue().getPassword()).isNull();
    }

    @Test void validationAndRevokedOperationPermissionsRejectBeforeNativeMutation(){
        fixture.permissions.addAll(Set.of("system:dict:list","system:dict:item:edit"));var controller=mock(DictionaryAdminController.class);
        var gateway=registry(factory.dictionaryAgentResources(controller,fixture.access,fixture.json,validation.getValidator(),jdbc));
        var args=fixture.json.createObjectNode().put("operation","updateItem").put("id","9").put("typeId","77");args.putObject("data").put("label","").put("enabled",true);
        var call=fixture.call(fixture.platform);
        assertThatThrownBy(()->gateway.changeSnapshot(call,"dictionary",args)).isInstanceOf(AgentProblem.class);
        args.withObject("data").put("label","处理中");fixture.permissions.remove("system:dict:item:edit");
        assertThatThrownBy(()->gateway.mutate(call,"tool-3","dictionary",args)).isInstanceOf(AgentProblem.class);
        verifyNoInteractions(controller);
    }
    @Test void fileToolsExposeOnlyNativeOwnerMetadataAndRecheckPermissions(){
        fixture.permissions.addAll(Set.of("system:oss:list","system:oss:query"));
        var controller=mock(SysOssController.class);var file=new com.xinghuiTec.oss.entity.SysOss();
        file.setOssId(91L);file.setOriginalName("QA收据.pdf");file.setUrl("https://qa.invalid/private?signature=hidden");
        var page=new com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.xinghuiTec.oss.entity.SysOss>(1,20,1);page.setRecords(List.of(file));
        when(controller.getList(1,20,null,null,"createTime","desc")).thenReturn(Result.ok(page));
        var reads=new NativeReadAgentResources(fixture.access,fixture.json,validation.getValidator(),jdbc);
        var gateway=registry(reads.fileAgentResources(controller));var call=fixture.call(fixture.a);
        var result=gateway.query(call,"files",fixture.json.createObjectNode());
        assertThat(result.path("records").get(0).path("originalName").asText()).isEqualTo("QA收据.pdf");
        assertThat(result.toString()).doesNotContain("signature","https:");
        assertThat(gateway.resources(call).get(0).path("readOnly").asBoolean()).isTrue();
        assertThatThrownBy(()->gateway.query(call,"files",fixture.json.createObjectNode().put("pageSize",101))).isInstanceOf(AgentProblem.class);
        fixture.permissions.removeAll(Set.of("system:oss:list","system:oss:query"));
        assertThatThrownBy(()->gateway.query(call,"files",fixture.json.createObjectNode())).isInstanceOf(AgentProblem.class);
        verify(controller,times(1)).getList(anyInt(),anyInt(),any(),any(),anyString(),anyString());
    }
    @Test void platformReadToolsDoNotExposeOtherTenantsToTenantIdentity(){
        fixture.permissions.add("system:tenant:list");var controller=mock(SysTenantController.class);
        var reads=new NativeReadAgentResources(fixture.access,fixture.json,validation.getValidator(),jdbc);
        var gateway=registry(reads.tenantAgentResources(controller));var call=fixture.call(fixture.a);
        assertThat(gateway.resources(call)).isEmpty();
        assertThatThrownBy(()->gateway.query(call,"tenants",fixture.json.createObjectNode())).isInstanceOf(AgentProblem.class);
        verifyNoInteractions(controller);
    }
    @Test void menuQueriesUseTheNativeAssignedPermissionWithoutInventingAnExtraIdentityRule(){
        fixture.permissions.add("system:menu:list");var controller=mock(SysMenuController.class);
        when(controller.getMenuList(any())).thenReturn(Result.ok(List.of()));
        var reads=new NativeReadAgentResources(fixture.access,fixture.json,validation.getValidator(),jdbc);
        var gateway=registry(reads.menuAgentResources(controller));var call=fixture.call(fixture.a);
        assertThat(gateway.resources(call)).hasSize(1);
        assertThat(gateway.query(call,"menus",fixture.json.createObjectNode())).isEmpty();
        verify(controller).getMenuList(any());
    }
    @Test void partialMenuEditKeepsRoutesAndCannotInjectAnotherTargetId(){
        fixture.permissions.addAll(Set.of("system:menu:list","system:menu:query","system:menu:edit"));
        var controller=mock(SysMenuController.class);var menu=new com.xinghuiTec.domain.entity.SysMenu();
        menu.setMenuId(18L);menu.setMenuName("商品管理");menu.setMenuType("C");menu.setPath("products");menu.setComponent("views/products/index");menu.setOrderNum(4);
        when(controller.getMenuById(18L)).thenReturn(Result.ok(menu));when(controller.updateMenu(any())).thenReturn(Result.ok());
        jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,is_deleted INT)");jdbc.update("INSERT INTO sys_menu VALUES(18,0)");
        var reads=new NativeReadAgentResources(fixture.access,fixture.json,validation.getValidator(),jdbc);
        var gateway=registry(reads.menuAgentResources(controller));var call=fixture.call(fixture.a);
        var args=fixture.json.createObjectNode().put("operation","update").put("id","18");args.putObject("data").put("menuName","产品管理");
        assertThat(tx.execute(s->gateway.changeSnapshot(call,"menus",args)).path("path").asText()).isEqualTo("products");
        gateway.mutate(call,"menu-write","menus",args);
        var value=ArgumentCaptor.forClass(com.xinghuiTec.domain.dto.SysMenuAddDTO.class);verify(controller).updateMenu(value.capture());
        assertThat(value.getValue().getMenuId()).isEqualTo(18L);assertThat(value.getValue().getComponent()).isEqualTo("views/products/index");
        args.withObject("data").put("menuId",99);assertThatThrownBy(()->gateway.mutate(call,"bad-target","menus",args)).isInstanceOf(AgentProblem.class);
        fixture.permissions.remove("system:menu:edit");assertThatThrownBy(()->gateway.mutate(call,"revoked","menus",args)).isInstanceOf(AgentProblem.class);
        verify(controller,times(1)).updateMenu(any());
    }
    @Test void formPublishingUsesNativeRevisionAndNeverGrantsTenantIdentityDesignAccess(){
        fixture.permissions.addAll(Set.of("lowcode:form:query","lowcode:form:publish"));
        var controller=mock(com.xinghuiTec.lowcode.LowcodeFormController.class);
        when(controller.get(2)).thenReturn(Result.ok(Map.of("id","2","revision",7,"status","draft")));
        when(controller.publish(eq(2L),any())).thenReturn(Result.ok(Map.of("id","2","revision",8,"publishedVersion",1)));
        jdbc.execute("CREATE TABLE sys_lowcode_form(id BIGINT PRIMARY KEY,is_deleted INT)");jdbc.update("INSERT INTO sys_lowcode_form VALUES(2,0)");
        var reads=new NativeReadAgentResources(fixture.access,fixture.json,validation.getValidator(),jdbc);
        var gateway=registry(reads.formDefinitionAgentResources(controller));var args=fixture.json.createObjectNode().put("operation","publish").put("id","2");args.putObject("data").put("revision",7);
        var call=fixture.call(fixture.platform);assertThat(tx.execute(s->gateway.changeSnapshot(call,"form-definitions",args)).path("revision").asInt()).isEqualTo(7);
        assertThat(gateway.mutate(call,"publish","form-definitions",args).path("publishedVersion").asInt()).isEqualTo(1);
        var value=ArgumentCaptor.forClass(com.xinghuiTec.lowcode.LowcodeService.VersionRequest.class);verify(controller).publish(eq(2L),value.capture());assertThat(value.getValue().revision()).isEqualTo(7);
        assertThatThrownBy(()->gateway.mutate(fixture.call(fixture.a),"tenant-publish","form-definitions",args)).isInstanceOf(AgentProblem.class);
        fixture.permissions.remove("lowcode:form:publish");assertThatThrownBy(()->gateway.mutate(call,"revoked-publish","form-definitions",args)).isInstanceOf(AgentProblem.class);
        verify(controller,times(1)).publish(anyLong(),any());
    }
    @Test void inboxRetainsNativeOwnerScopeAndRejectsCallerSuppliedRecipients(){
        fixture.json.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var controller=mock(NotificationInboxController.class);var item=new com.xinghuiTec.notification.NotificationInboxService.Item(51,"qa","qa-51","QA通知","验收消息","/system/user",java.time.LocalDateTime.now(),null);
        when(controller.detail(51)).thenReturn(Result.ok(item));
        jdbc.execute("CREATE TABLE sys_notification_inbox(notification_id BIGINT PRIMARY KEY,tenant_id VARCHAR(20),recipient_user_id BIGINT)");jdbc.update("INSERT INTO sys_notification_inbox VALUES(51,'tenant_a',1)");
        var gateway=registry(new InboxAgentResources(controller,fixture.access,fixture.json,jdbc));var call=fixture.call(fixture.a);
        var args=fixture.json.createObjectNode().put("operation","markRead").put("id","51");
        assertThat(tx.execute(s->gateway.changeSnapshot(call,"inbox",args)).path("title").asText()).isEqualTo("QA通知");
        assertThatThrownBy(()->tx.execute(s->gateway.changeSnapshot(fixture.call(fixture.b),"inbox",args))).isInstanceOf(AgentProblem.class);
        args.put("recipientUserId","2");assertThatThrownBy(()->gateway.mutate(call,"bad-recipient","inbox",args)).isInstanceOf(AgentProblem.class);args.remove("recipientUserId");
        assertThat(gateway.mutate(call,"read","inbox",args).path("read").asBoolean()).isTrue();verify(controller).markRead(51);
    }
}
