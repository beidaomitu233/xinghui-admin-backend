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
}
