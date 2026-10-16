package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiApplicationReferences;
import com.xinghuiTec.ai.contract.AiTypes.ModelVersionRef;
import com.xinghuiTec.ai.contract.AiTypes.ResourceVersionRef;
import com.xinghuiTec.ai.contract.AiTypes.TemplateVersionRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Single shared database guard, including the dependency change's transaction commit. */
@Service
public class JdbcAiApplicationReferences implements AiApplicationReferences {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcAiApplicationReferences(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public List<ApplicationReference> modelReferences(ModelVersionRef model) {
        return references("MODEL", model.modelRef(), model.configVersion());
    }

    @Override
    public List<ApplicationReference> templateReferences(TemplateVersionRef template) {
        return references("TEMPLATE", template.templateId(), template.templateVersionId());
    }

    @Override
    public List<ApplicationReference> resourceReferences(ResourceVersionRef resource) {
        return references("RESOURCE", resource.resourceId(), resource.resourceVersionId());
    }

    @Override
    public <T> T withReferenceGuard(Supplier<T> operation) {
        Objects.requireNonNull(operation);
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Reference mutation requires a writable transaction");
        }
        return transaction.execute(status -> {
            List<Integer> rows = jdbc.query("SELECT guard_id FROM sys_ai_reference_guard"
                    + " WHERE guard_id=1 FOR UPDATE", (rs, index) -> rs.getInt(1));
            if (rows.size() != 1) throw new IllegalStateException("Reference guard unavailable");
            return operation.get();
        });
    }

    private List<ApplicationReference> references(String kind, String referenceId, String version) {
        // A locking read uses the current committed state even under MySQL REPEATABLE READ.
        // Merely querying a prior transaction snapshot after waiting for the guard is insufficient.
        return jdbc.query("""
                SELECT a.app_id,a.published_version_id,a.name
                  FROM sys_ai_application a
                  JOIN sys_ai_application_reference r ON r.app_version_id=a.published_version_id
                 WHERE a.enabled=TRUE AND r.reference_kind=? AND r.reference_id=? AND r.reference_version=?
                 ORDER BY a.app_id FOR UPDATE
                """, (rs, index) -> new ApplicationReference(Long.toString(rs.getLong(1)),
                Long.toString(rs.getLong(2)), rs.getString(3)), kind, referenceId, version);
    }
}
