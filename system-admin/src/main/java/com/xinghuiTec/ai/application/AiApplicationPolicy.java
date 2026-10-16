package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiProviderRuntime.ModelBinding;
import com.xinghuiTec.ai.contract.AiTypes.Requirements;
import com.xinghuiTec.ai.contract.AiTypes.TemplateVersionRef;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validated configuration metadata only. No sample input, prompt, output or credentials. */
public record AiApplicationPolicy(ModelMode modelMode, List<ModelBinding> models, boolean modelFallback,
                                  TemplateVersionRef template, Set<String> inputNames,
                                  Requirements requirements, Scope scope,
                                  Set<String> allowedSubjects, Set<String> allowedAuthorities,
                                  String formId, int timeoutSeconds, int maxAttempts,
                                  int maxConcurrent, int maxTokens) {
    public enum ModelMode { FIXED, ROUND_ROBIN }
    public enum Scope { FRONTEND, BACKGROUND, BOTH }

    public AiApplicationPolicy {
        Objects.requireNonNull(modelMode);
        Objects.requireNonNull(requirements);
        Objects.requireNonNull(scope);
        models = List.copyOf(models);
        inputNames = Set.copyOf(inputNames);
        allowedSubjects = Set.copyOf(allowedSubjects);
        allowedAuthorities = Set.copyOf(allowedAuthorities);
        if (models.size() > 16 || models.stream().map(ModelBinding::model).distinct().count() != models.size()
                || inputNames.size() > 32 || allowedSubjects.size() > 128 || allowedAuthorities.size() > 128
                || timeoutSeconds < 1 || timeoutSeconds > 120 || maxAttempts < 1 || maxAttempts > 8
                || maxConcurrent < 1 || maxConcurrent > 10 || maxTokens < 1 || maxTokens > 4096) {
            throw new IllegalArgumentException("Invalid application bounds");
        }
        inputNames.forEach(value -> requirePattern(value, "[A-Za-z][A-Za-z0-9_]{0,63}"));
        allowedSubjects.forEach(value -> requirePattern(value, "[A-Za-z0-9][A-Za-z0-9_:.-]{0,127}"));
        allowedAuthorities.forEach(value -> requirePattern(value, "[A-Za-z][A-Za-z0-9_:*.-]{0,127}"));
        if (formId != null) requirePattern(formId, "[1-9][0-9]{0,18}");
    }

    /** Incomplete drafts are allowed, but these prerequisites are mandatory before testing/publishing. */
    public void requireComplete() {
        if (models.isEmpty() || template == null || (allowedSubjects.isEmpty() && allowedAuthorities.isEmpty())
                || (modelMode == ModelMode.FIXED && !modelFallback && models.size() != 1)) {
            throw new IllegalArgumentException("Incomplete application configuration");
        }
        if (requirements.tools()) throw new IllegalArgumentException("Tools require the second phase");
    }

    /** A configuration administrator is not implicitly a permitted business caller. */
    public boolean permits(TrustedActor actor) {
        boolean service = actor.servicePrincipalRef() != null;
        if ((scope == Scope.FRONTEND && service) || (scope == Scope.BACKGROUND && !service)) return false;
        return permitsSubject(actor);
    }

    /** Management TEST ignores business scope, but never ignores these actual subject permissions. */
    public boolean permitsSubject(TrustedActor actor) {
        boolean service = actor.servicePrincipalRef() != null;
        String subject = service ? actor.servicePrincipalRef() : actor.subjectId();
        return allowedSubjects.contains(subject)
                || actor.authorities().stream().anyMatch(allowedAuthorities::contains);
    }

    public List<Integer> attemptModelOrder(int firstIndex) {
        if (firstIndex < 0 || firstIndex >= models.size()) throw new IllegalArgumentException("Invalid first model");
        if (modelMode == ModelMode.FIXED && firstIndex != 0) throw new IllegalArgumentException("Fixed model must be first");
        if (!modelFallback) return List.of(firstIndex);
        var order = new java.util.ArrayList<Integer>();
        if (modelMode == ModelMode.FIXED) {
            for (int index = 0; index < models.size(); index++) order.add(index);
        } else {
            for (int offset = 0; offset < models.size(); offset++) order.add((firstIndex + offset) % models.size());
        }
        return List.copyOf(order);
    }

    private static void requirePattern(String value, String pattern) {
        if (value == null || !value.matches(pattern)) throw new IllegalArgumentException("Invalid application metadata");
    }
}
