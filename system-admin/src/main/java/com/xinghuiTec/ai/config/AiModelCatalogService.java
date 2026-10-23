package com.xinghuiTec.ai.config;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import java.time.Instant;
import java.util.*;
import static com.xinghuiTec.ai.config.AiModelCatalogDtos.*;
import static com.xinghuiTec.ai.config.AiModelCatalogTransport.failure;

/** Official directory metadata with exact actor/endpoint/credential-bound receipts, never inference admission. */
@Service
public class AiModelCatalogService {
    private final AiConfigurationService configs;
    private final AiModelCatalogTransport transport;
    private final AiModelCatalogStore store;
    private com.xinghuiTec.ai.application.AiCallLogStore callLogs;
    @Autowired(required=false) public void callLogs(com.xinghuiTec.ai.application.AiCallLogStore logs){this.callLogs=logs;}

    /** Nonpersistent local-test seam. Spring always requires the database-backed store. */
    public AiModelCatalogService(AiConfigurationService configs, AiModelCatalogTransport transport) {
        this(configs,transport,null);
    }
    @Autowired
    public AiModelCatalogService(AiConfigurationService configs,AiModelCatalogTransport transport,AiModelCatalogStore store) {
        this.configs = configs;
        this.transport = transport;
        this.store=store;
    }

    public Page catalog(String search, int pageNum, int pageSize) {
        return catalog(search,pageNum,pageSize,null);
    }
    public Page catalog(String search,int pageNum,int pageSize,TrustedActor actor) {
        String query = query(search, pageNum, pageSize);
        return page(fetch("openrouter",null,actor), query, pageNum, pageSize, "PUBLIC", "openrouter", null, null, null);
    }

    public Page draftScan(DraftScan request) {
        return draftScan(request,null);
    }
    public Page draftScan(DraftScan request,TrustedActor actor) {
        if (request == null || request.apiKey() == null || request.apiKey().isBlank())
            throw failure(400, "INVALID_INPUT");
        String query = query(request.search(), request.pageNum(), request.pageSize());
        var preset=preset(request.provider());
        if(request.endpointUrl()!=null&&!request.endpointUrl().equals(preset.chatEndpoint()))throw failure(422,"ENDPOINT_UNSUPPORTED");
        if(store!=null)owner(actor);
        var models=fetch(request.provider(),request.apiKey(),actor);String source=source(request.provider());
        var receipt=store==null?null:store.create(owner(actor),preset.code(),preset.chatEndpoint(),credential(preset.code(),preset.chatEndpoint(),request.apiKey()),source,models);
        return page(models,query,request.pageNum(),request.pageSize(),source,request.provider(),null,null,null,
                receipt==null?Instant.now():receipt.refreshedAt(),receipt==null?null:receipt.id());
    }

    public Page scan(Scan request) {
        return scan(request,null);
    }
    public Page scan(Scan request,TrustedActor actor) {
        if (request == null || request.expectedRevision() < 0) throw failure(400, "INVALID_INPUT");
        String query = query(request.search(), request.pageNum(), request.pageSize());
        if(store!=null)owner(actor);
        long configId = decimalId(request.configId());
        decimalId(request.keyId());
        var head = head(configId);
        verify(head, request.expectedRevision());
        final AiConfigurationDtos.Snapshot snapshot;
        try { snapshot = configs.snapshot(configId, head.draft()); }
        catch (RuntimeException unavailable) { throw failure(409, "CONFIG_UNAVAILABLE"); }
        final AiProviderPresets.Preset preset;
        try { preset = AiProviderPresets.require(snapshot.providerCode()); }
        catch (AiConfigurationException invalid) { throw failure(422, "PROVIDER_UNSUPPORTED"); }
        if (!preset.chatEndpoint().equals(snapshot.endpointUrl()))
            throw failure(422, "PROVIDER_UNSUPPORTED");
        var keys = snapshot.keys() == null ? List.<AiConfigurationDtos.KeySpec>of() : snapshot.keys();
        var selected = keys.stream().filter(k -> request.keyId().equals(k.keyRef())).findFirst()
                .orElseThrow(() -> failure(404, "KEY_NOT_FOUND"));
        final String secret;
        try { secret = configs.resolveSecret(selected); }
        catch (RuntimeException unavailable) { throw failure(503, "CREDENTIAL_UNAVAILABLE"); }
        if (secret == null || secret.isBlank()) throw failure(503, "CREDENTIAL_UNAVAILABLE");
        // Recheck before sending credentials and after receiving the remote result.
        same(head, head(configId));
        var models = fetch(preset.code(),secret,actor);
        same(head, head(configId));
        var receipt=store==null?null:store.create(owner(actor),preset.code(),preset.chatEndpoint(),credential(preset.code(),preset.chatEndpoint(),secret),source(preset.code()),models);
        if(receipt!=null)attach(new Attach(request.configId(),request.keyId(),head.revision(),receipt.id()),actor);
        return page(models, query, request.pageNum(), request.pageSize(),source(preset.code()), preset.code(),
                request.configId(), request.keyId(), head.revision(),receipt==null?Instant.now():receipt.refreshedAt(),receipt==null?null:receipt.id());
    }

    public StoredCatalog attach(Attach input,TrustedActor actor) {
        if(store==null)throw failure(503,"UNAVAILABLE");
        if(input==null||input.expectedRevision()<0)throw failure(400,"INVALID_INPUT");
        long config=decimalId(input.configId());decimalId(input.keyId());
        try{if(!UUID.fromString(input.catalogScanId()).toString().equals(input.catalogScanId()))throw new IllegalArgumentException();}
        catch(RuntimeException invalid){throw failure(400,"INVALID_INPUT");}
        String owner=owner(actor);
        return configs.settingsGuard(()->store.transaction(()-> {
            AiConfigurationService.Head current;
            try{current=configs.head(config,true);}catch(RuntimeException unavailable){throw failure(409,"CONFIG_UNAVAILABLE");}
            verify(current,input.expectedRevision());var snapshot=configs.snapshot(config,current.draft());
            var key=snapshot.keys().stream().filter(k->k.keyRef().equals(input.keyId())).findFirst().orElseThrow(()->failure(404,"KEY_NOT_FOUND"));
            var receipt=store.locked(input.catalogScanId());
            if(!owner.equals(receipt.owner()))throw failure(403,"CATALOG_FORBIDDEN");
            if(!Instant.now().isBefore(receipt.expiresAt()))throw failure(410,"CATALOG_EXPIRED");
            if(!receipt.provider().equals(snapshot.providerCode())||!receipt.endpoint().equals(snapshot.endpointUrl()))throw failure(409,"CATALOG_CHANGED");
            String material;
            try{material=configs.resolveSecret(key);}catch(RuntimeException unavailable){throw failure(503,"CREDENTIAL_UNAVAILABLE");}
            if(!receipt.credentialFingerprint().equals(credential(snapshot.providerCode(),snapshot.endpointUrl(),material)))throw failure(409,"CATALOG_CHANGED");
            store.prune(config,snapshot.keys().stream().map(AiConfigurationDtos.KeySpec::keyRef).toList());
            store.bind(config,input.keyId(),receipt.id());
            return stored(input.configId(),null,"",1,20);
        }));
    }

    public Page savedScan(String scanId,TrustedActor actor,String search,int pageNum,int pageSize) {
        if(store==null)throw failure(503,"UNAVAILABLE");String query=query(search,pageNum,pageSize);
        try{if(!UUID.fromString(scanId).toString().equals(scanId))throw new IllegalArgumentException();}catch(RuntimeException invalid){throw failure(400,"INVALID_INPUT");}
        var receipt=store.get(scanId);if(!owner(actor).equals(receipt.owner()))throw failure(403,"CATALOG_FORBIDDEN");
        if(!Instant.now().isBefore(receipt.expiresAt()))throw failure(410,"CATALOG_EXPIRED");
        return page(receipt.catalog(),query,pageNum,pageSize,receipt.source(),receipt.provider(),null,null,null,receipt.refreshedAt(),receipt.id());
    }

    public StoredCatalog stored(String configId,String keyId,String search,int pageNum,int pageSize) {
        return stored(configId,keyId,search,pageNum,pageSize,null,null);
    }
    public StoredCatalog stored(String configId,String keyId,String search,int pageNum,int pageSize,String orderBy,String order) {
        if(store==null)throw failure(503,"UNAVAILABLE");
        String query=query(search,pageNum,pageSize);long config=decimalId(configId);if(keyId!=null)decimalId(keyId);
        var current=head(config);if(current.draft()==null)throw failure(409,"CONFIG_UNAVAILABLE");
        var snapshot=configs.snapshot(config,current.draft());
        var keys=snapshot.keys()==null?List.<AiConfigurationDtos.KeySpec>of():snapshot.keys();
        if(keyId!=null&&keys.stream().noneMatch(key->key.keyRef().equals(keyId)))throw failure(404,"KEY_NOT_FOUND");
        var bound=store.bound(config,keyId);var sources=new ArrayList<CatalogSource>();var valid=new ArrayList<AiModelCatalogStore.Bound>();
        for(var binding:bound) {
            var receipt=binding.receipt();boolean matches=false;
            var key=keys.stream().filter(k->k.keyRef().equals(binding.keyId())).findFirst().orElse(null);
            if(key!=null&&!"ARCHIVED".equals(current.status())&&receipt.provider().equals(snapshot.providerCode())&&receipt.endpoint().equals(snapshot.endpointUrl())) {
                try{matches=receipt.credentialFingerprint().equals(credential(snapshot.providerCode(),snapshot.endpointUrl(),configs.resolveSecret(key)));}
                catch(RuntimeException unavailable){matches=false;}
            }
            if(matches)valid.add(binding);
            sources.add(new CatalogSource(receipt.id(),binding.keyId(),receipt.refreshedAt(),receipt.source(),matches,
                    receipt.catalog().scannedCount(),receipt.catalog().filteredCount(),receipt.catalog().filterReasons(),receipt.catalogEndpoint()));
        }
        CatalogState state=bound.isEmpty()?CatalogState.NOT_FETCHED:valid.isEmpty()?CatalogState.STALE:
                valid.size()>=(keyId==null?keys.size():1)?CatalogState.FETCHED:CatalogState.PARTIAL;
        var selected=valid.isEmpty()?bound:valid;var models=new TreeMap<String,Model>();var reasons=new TreeMap<String,Integer>();int scanned=0,filtered=0;
        Instant refreshed=null;
        for(var binding:selected) {
            var receipt=binding.receipt();if(refreshed==null||receipt.refreshedAt().isAfter(refreshed))refreshed=receipt.refreshedAt();
            for(var model:receipt.catalog().models())models.putIfAbsent(model.code(),model);
            if(models.size()>5000)throw failure(413,"CATALOG_LIMIT");
            scanned+=receipt.catalog().scannedCount();filtered+=receipt.catalog().filteredCount();receipt.catalog().filterReasons().forEach((reason,count)->reasons.merge(reason,count,Integer::sum));
        }
        var after=head(config);if(after.revision()!=current.revision()||!Objects.equals(after.draft(),current.draft()))throw failure(409,"CONFIG_CHANGED");
        var combined=new AiModelCatalogTransport.Catalog(List.copyOf(models.values()),scanned,filtered,Map.copyOf(reasons));
        return new StoredCatalog(configId,current.revision(),snapshot.providerCode(),snapshot.endpointUrl(),state,
                page(combined,query,pageNum,pageSize,"SAVED_CATALOG",snapshot.providerCode(),configId,keyId,current.revision(),refreshed,null,orderBy,order),sources);
    }
    private AiProviderPresets.Preset preset(String provider){try{return AiProviderPresets.require(provider);}catch(RuntimeException invalid){throw failure(422,"PROVIDER_UNSUPPORTED");}}
    private AiModelCatalogTransport.Catalog fetch(String provider,String secret,TrustedActor actor){
        if(callLogs!=null&&actor==null)throw failure(403,"FORBIDDEN");
        String call=callLogs==null?null:callLogs.beginDiscovery(actor,provider);long start=System.nanoTime();boolean success=false;
        try{var result=transport.fetch(provider,secret);success=true;return result;}
        finally{if(callLogs!=null)callLogs.completeDiscovery(call,success,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
    }
    private String source(String provider){return "openrouter".equals(provider)?"USER_FILTERED":"PROVIDER_KEY";}
    private String owner(TrustedActor actor){if(actor==null||actor.servicePrincipalRef()!=null)throw failure(403,"FORBIDDEN");return configs.settingsFingerprint("ai:catalog-owner:v1",List.of(actor.subjectId(),actor.tenantId()));}
    private String credential(String provider,String endpoint,String secret){if(secret==null||secret.isBlank())throw failure(503,"CREDENTIAL_UNAVAILABLE");return configs.settingsFingerprint("ai:catalog-credential:v1",List.of(provider,endpoint,secret));}

    private AiConfigurationService.Head head(long configId) {
        try { return configs.head(configId, false); }
        catch (RuntimeException unavailable) { throw failure(409, "CONFIG_UNAVAILABLE"); }
    }

    private void verify(AiConfigurationService.Head head, int revision) {
        if (head.revision() != revision) throw failure(409, "CONFIG_CHANGED");
        if (head.draft() == null || "ARCHIVED".equals(head.status())) throw failure(409, "CONFIG_UNAVAILABLE");
    }

    private void same(AiConfigurationService.Head original, AiConfigurationService.Head current) {
        verify(current, original.revision());
        if (!Objects.equals(original.draft(), current.draft())) throw failure(409, "CONFIG_CHANGED");
    }

    private long decimalId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw failure(400, "INVALID_INPUT");
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw failure(400, "INVALID_INPUT"); }
    }

    private String query(String search, int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > 100 || search != null
                && (search.length() > 128 || search.chars().anyMatch(Character::isISOControl)))
            throw failure(400, "INVALID_INPUT");
        return search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
    }

    private Page page(AiModelCatalogTransport.Catalog catalog, String query, int pageNum, int pageSize,
                      String source, String provider, String configId, String keyId, Integer revision) {
        return page(catalog,query,pageNum,pageSize,source,provider,configId,keyId,revision,Instant.now(),null);
    }
    private Page page(AiModelCatalogTransport.Catalog catalog,String query,int pageNum,int pageSize,String source,String provider,
                      String configId,String keyId,Integer revision,Instant refreshedAt,String scanId) {
        return page(catalog,query,pageNum,pageSize,source,provider,configId,keyId,revision,refreshedAt,scanId,null,null);
    }
    private Page page(AiModelCatalogTransport.Catalog catalog,String query,int pageNum,int pageSize,String source,String provider,
                      String configId,String keyId,Integer revision,Instant refreshedAt,String scanId,String orderBy,String order) {
        var filtered = catalog.models().stream().filter(model -> model.code().toLowerCase(Locale.ROOT).contains(query)
                || model.name().toLowerCase(Locale.ROOT).contains(query)).toList();
        if ((orderBy!=null&&!orderBy.isBlank()) || (order!=null&&!order.isBlank())) {
            if (!("asc".equals(order)||"desc".equals(order)) || orderBy==null) throw failure(400,"INVALID_INPUT");
            Comparator<Model> comparator=switch(orderBy) {
                case "code" -> Comparator.comparing(Model::code);
                case "name" -> Comparator.comparing(Model::name);
                case "contextLength" -> Comparator.comparing(Model::contextLength,Comparator.nullsFirst(Long::compareTo));
                case "maxCompletionTokens" -> Comparator.comparing(Model::maxCompletionTokens,Comparator.nullsFirst(Long::compareTo));
                default -> throw failure(400,"INVALID_INPUT");
            };
            comparator=comparator.thenComparing(Model::code);
            if("desc".equals(order))comparator=comparator.reversed();
            filtered=filtered.stream().sorted(comparator).toList();
        }
        long offset = (long) (pageNum - 1) * pageSize;
        var records = offset >= filtered.size() ? List.<Model>of()
                : filtered.subList((int) offset, Math.min(filtered.size(), (int) offset + pageSize));
        return new Page(List.copyOf(records), filtered.size(), pageNum, pageSize, refreshedAt,
                source, provider, configId, keyId, revision, false,
                catalog.scannedCount(), catalog.filteredCount(), catalog.filterReasons(),scanId);
    }
}
