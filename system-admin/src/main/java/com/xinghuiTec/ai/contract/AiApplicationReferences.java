package com.xinghuiTec.ai.contract;

import java.util.List;
import java.util.function.Supplier;

import static com.xinghuiTec.ai.contract.AiTypes.*;

/** B implements this; missing service must fail dangerous changes closed, never imply empty references. */
public interface AiApplicationReferences {
    List<ApplicationReference> modelReferences(ModelVersionRef model);
    List<ApplicationReference> templateReferences(TemplateVersionRef template);
    List<ApplicationReference> resourceReferences(ResourceVersionRef resource);

    /**
     * Run reference checks AND dependency mutation inside this guard. B application publish, enable,
     * rollback and reference registration must acquire the same cross-instance DB transaction lock.
     * The lock covers commit (not merely the query); a JVM synchronized block is insufficient.
     */
    <T> T withReferenceGuard(Supplier<T> operation);

    record ApplicationReference(String appId, String appVersionId, String name) {
        public ApplicationReference { required(appId); required(appVersionId); required(name); }
    }
}
