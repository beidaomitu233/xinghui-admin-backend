package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.xinghuiTec.ai.config.AiModelCatalogTransport.failure;

/** Database-backed metadata only. Opaque scan IDs confer no read, credential or inference permission. */
@Component
public class AiModelCatalogStore {
    record Receipt(String id,String owner,String provider,String endpoint,String catalogEndpoint,String credentialFingerprint,String source,
                   Instant refreshedAt,Instant expiresAt,AiModelCatalogTransport.Catalog catalog) {
        @Override public String toString(){return "CatalogReceipt[REDACTED]";}
    }
    record Bound(String keyId,Receipt receipt) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json=new ObjectMapper();
    private final TransactionTemplate transaction;
    public AiModelCatalogStore(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;transaction=new TransactionTemplate(manager);}
    Receipt create(String owner,String provider,String endpoint,String credentialFingerprint,String source,AiModelCatalogTransport.Catalog catalog) {
        Instant now=Instant.ofEpochMilli(System.currentTimeMillis());String id=UUID.randomUUID().toString();
        try {
            String models=json.writeValueAsString(catalog.models());
            if(models.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>AiModelCatalogTransport.MAX_BYTES)throw failure(413,"CATALOG_LIMIT");
            jdbc.update("INSERT INTO sys_ai_channel_catalog_scan(scan_id,owner_scope,provider_code,endpoint_url,catalog_endpoint_url,credential_fingerprint,source_code,refreshed_at_ms,expires_at_ms,models_json,scanned_count,filtered_count,filter_reasons_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,owner,provider,endpoint,AiProviderPresets.require(provider).modelsEndpoint(),credentialFingerprint,source,now.toEpochMilli(),now.plus(Duration.ofHours(24)).toEpochMilli(),models,catalog.scannedCount(),catalog.filteredCount(),json.writeValueAsString(catalog.filterReasons()));
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw failure(503,"UNAVAILABLE");}
        return new Receipt(id,owner,provider,endpoint,AiProviderPresets.require(provider).modelsEndpoint(),credentialFingerprint,source,now,now.plus(Duration.ofHours(24)),catalog);
    }
    Receipt get(String id){
        return get(id,false);
    }
    Receipt locked(String id){return get(id,true);}
    private Receipt get(String id,boolean lock){
        var rows=jdbc.query("SELECT * FROM sys_ai_channel_catalog_scan WHERE scan_id=?"+(lock?" FOR UPDATE":""),(rs,index)->receipt(rs),id);
        if(rows.size()!=1)throw failure(404,"CATALOG_NOT_FOUND");return rows.get(0);
    }
    List<Bound> bound(long config,String keyId){
        String sql="SELECT b.key_ref,s.* FROM sys_ai_channel_catalog_binding b JOIN sys_ai_channel_catalog_scan s ON s.scan_id=b.scan_id WHERE b.config_id=?";
        Object[] args=keyId==null?new Object[]{config}:new Object[]{config,keyId};
        if(keyId!=null)sql+=" AND b.key_ref=?";
        var rows=jdbc.query(sql+" ORDER BY s.refreshed_at_ms DESC,b.key_ref LIMIT 17",(rs,index)->new Bound(rs.getString("key_ref"),receipt(rs)),args);
        if(rows.size()>16)throw failure(413,"CATALOG_LIMIT");return rows;
    }
    void bind(long config,String keyId,String scanId){
        jdbc.update("DELETE FROM sys_ai_channel_catalog_binding WHERE config_id=? AND key_ref=?",config,keyId);
        jdbc.update("INSERT INTO sys_ai_channel_catalog_binding(config_id,key_ref,scan_id) VALUES(?,?,?)",config,keyId,scanId);
    }
    void prune(long config,List<String> currentKeys){
        if(currentKeys.isEmpty()){jdbc.update("DELETE FROM sys_ai_channel_catalog_binding WHERE config_id=?",config);return;}
        var parameters=new ArrayList<Object>();parameters.add(config);parameters.addAll(currentKeys);
        jdbc.update("DELETE FROM sys_ai_channel_catalog_binding WHERE config_id=? AND key_ref NOT IN ("+String.join(",",Collections.nCopies(currentKeys.size(),"?"))+")",parameters.toArray());
    }
    <T>T transaction(Supplier<T> action){return transaction.execute(status->action.get());}
    private Receipt receipt(java.sql.ResultSet row)throws java.sql.SQLException {
        try {
            var models=List.of(json.readValue(row.getString("models_json"),AiModelCatalogDtos.Model[].class));
            Map<String,Integer> reasons=json.readValue(row.getString("filter_reasons_json"),new TypeReference<>(){});
            return new Receipt(row.getString("scan_id"),row.getString("owner_scope"),row.getString("provider_code"),row.getString("endpoint_url"),row.getString("catalog_endpoint_url"),
                    row.getString("credential_fingerprint"),row.getString("source_code"),Instant.ofEpochMilli(row.getLong("refreshed_at_ms")),Instant.ofEpochMilli(row.getLong("expires_at_ms")),
                    new AiModelCatalogTransport.Catalog(models,row.getInt("scanned_count"),row.getInt("filtered_count"),Map.copyOf(reasons)));
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw failure(503,"UNAVAILABLE");}
    }
    @Scheduled(fixedDelay=3600000)
    public void expireUnattached(){
        jdbc.update("DELETE FROM sys_ai_channel_catalog_scan WHERE expires_at_ms<? AND NOT EXISTS (SELECT 1 FROM sys_ai_channel_catalog_binding b WHERE b.scan_id=sys_ai_channel_catalog_scan.scan_id) LIMIT 1000",System.currentTimeMillis());
    }
}
