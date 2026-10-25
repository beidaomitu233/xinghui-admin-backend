package com.xinghuiTec.ai.config;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import static com.xinghuiTec.ai.config.AiConfigurationDtos.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;

/** Executable tool metadata is bound to the current credential and official endpoint, not a UI switch. */
@Component
public class AiToolCapabilities {
    private final AiConfigurationService configs;
    private final AiModelCatalogStore catalogs;
    public AiToolCapabilities(AiConfigurationService configs,AiModelCatalogStore catalogs){this.configs=configs;this.catalogs=catalogs;}
    Capabilities forKey(long configId,Snapshot snapshot,ModelSpec model,KeySpec key) {
        var base=OpenRouterCapabilities.forModel(model);
        return new Capabilities(base.outputFormats(),base.streaming(),supports(configId,snapshot,model,key));
    }
    private boolean supports(long configId,Snapshot snapshot,ModelSpec model,KeySpec key) {
        return supports(configId,snapshot,model,key,candidate->candidate.supportedParameters().contains("tools"));
    }
    boolean supportsImages(long configId,Snapshot snapshot,ModelSpec model,KeySpec key){
        return supports(configId,snapshot,model,key,candidate->candidate.inputModalities().contains("image"));
    }
    private boolean supports(long configId,Snapshot snapshot,ModelSpec model,KeySpec key,java.util.function.Predicate<AiModelCatalogDtos.Model> capability) {
        try {
            if(!AiConfigurationService.associated(model,key)||!configs.keyUsable(key))return false;
            var preset=AiProviderPresets.require(snapshot.providerCode(),snapshot.endpointUrl());
            String fingerprint=configs.settingsFingerprint("ai:catalog-credential:v1",List.of(snapshot.providerCode(),snapshot.endpointUrl(),configs.resolveSecret(key)));
            Instant now=Instant.now();
            return catalogs.bound(configId,key.keyRef()).stream().map(AiModelCatalogStore.Bound::receipt).anyMatch(receipt->
                    snapshot.providerCode().equals(receipt.provider())&&snapshot.endpointUrl().equals(receipt.endpoint())
                            &&preset.modelsEndpoint().equals(receipt.catalogEndpoint())
                            // Expiry limits attaching an unbound scan ticket. A saved, unchanged binding remains
                            // metadata evidence, like the existing catalog view; it is not a generation guarantee.
                            &&fingerprint.equals(receipt.credentialFingerprint())&&!receipt.refreshedAt().isAfter(now)
                            &&receipt.catalog().models().stream().anyMatch(candidate->
                            candidate.code().equals(model.modelCode())&&capability.test(candidate)));
        }catch(RuntimeException unavailable){return false;}
    }
}
