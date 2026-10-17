package com.xinghuiTec.ai.config;

import com.xinghuiTec.ai.contract.AiApplicationReferences.ApplicationReference;
import java.util.List;
import java.util.Set;

/**
 * Called only inside the shared reference guard and configuration transaction.
 */
public interface AiSettingsRotation {
    String rotate(AiConfigurationDtos.Snapshot previous, AiConfigurationDtos.Snapshot replacement,
            Set<String> provenModels, List<ApplicationReference> references, String actor, boolean apply);
}
