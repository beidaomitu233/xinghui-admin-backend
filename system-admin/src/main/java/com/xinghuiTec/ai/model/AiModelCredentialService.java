package com.xinghuiTec.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.exception.ServiceException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiModelCredentialService {
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final AiModelConfigService models;
    public AiModelCredentialService(JdbcTemplate jdbc, SecretCipher cipher, AiModelConfigService models) {
        this.jdbc=jdbc; this.cipher=cipher; this.models=models;
    }
    public record Change(@NotNull String mode, @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @Size(max=8192) String apiKey,
                         boolean clearKey, @NotNull @Min(0) Integer revision) {
        @Override public String toString() { return "CredentialChange[secret=REDACTED]"; }
    }
    @Transactional
    public AiModelConfigService.View save(long id, Change change) {
        var model=models.get(id);
        if(change.revision()==null || change.revision()!=model.revision()) throw new ServiceException("配置已被修改，请刷新后重试");
        if(!"ENV".equals(change.mode()) && !"DB".equals(change.mode())) throw new ServiceException("凭据来源无效");
        if(change.clearKey() && change.apiKey()!=null && !change.apiKey().isBlank()) throw new ServiceException("清除密钥时不能同时填写新密钥");
        String stored=jdbc.queryForObject("SELECT credential_ciphertext FROM sys_ai_model_config WHERE model_id=?",String.class,id);
        if("ENV".equals(change.mode()) && change.apiKey()!=null && !change.apiKey().isBlank()) throw new ServiceException("请先选择数据库密钥来源");
        if(change.clearKey()) stored=null;
        else if(change.apiKey()!=null && !change.apiKey().isBlank()) stored=cipher.encrypt("ai:model:"+id,change.apiKey().trim());
        else if("DB".equals(change.mode()) && stored!=null) cipher.decrypt("ai:model:"+id,stored); // Keep only an authentic decryptable value.
        int updated=jdbc.update("UPDATE sys_ai_model_config SET credential_mode=?,credential_ciphertext=?,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE model_id=? AND revision=?",
                change.mode(),stored,id,change.revision());
        if(updated!=1) throw new ServiceException("配置已被修改，请刷新后重试");
        return models.get(id);
    }
    public String resolve(long id) {
        var model=models.get(id);
        if("DB".equals(model.credentialMode())) {
            String stored=jdbc.queryForObject("SELECT credential_ciphertext FROM sys_ai_model_config WHERE model_id=?",String.class,id);
            if(stored==null) throw new ServiceException("模型尚未配置数据库密钥");
            return cipher.decrypt("ai:model:"+id,stored);
        }
        if(!"ENV".equals(model.credentialMode())) throw new ServiceException("模型凭据来源无效");
        String value=model.credentialEnvName()==null ? null : System.getenv(model.credentialEnvName());
        if(value==null || value.isBlank()) throw new ServiceException("模型环境变量凭据不可用");
        return value;
    }
}
