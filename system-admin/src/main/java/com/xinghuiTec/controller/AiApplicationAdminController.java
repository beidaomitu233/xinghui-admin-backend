package com.xinghuiTec.controller;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.time.Instant;

/** Platform management only. Bodies/results are intentionally excluded from generic @Log. */
@RestController
@RequestMapping("/system/ai/applications")
public class AiApplicationAdminController {
    private final AiApplicationStore store;
    private final AiApplicationService service;
    private final AiInvocationIdentity identity;
    private final ObjectProvider<AiProviderRuntime> providers;

    public AiApplicationAdminController(AiApplicationStore store, AiApplicationService service,
                                         AiInvocationIdentity identity, ObjectProvider<AiProviderRuntime> providers) {
        this.store = store;
        this.service = service;
        this.identity = identity;
        this.providers = providers;
    }

    public record DraftChange(Integer revision, AiApplicationStore.Draft draft) {}
    public record Publish(Integer revision, List<String> testRequestIds) {}
    public record Enabled(Integer revision, Boolean enabled) {}
    public record Rollback(Integer revision, String appVersionId) {}

    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:list')")
    public Result<AiApplicationStore.Page> page(@RequestParam(defaultValue = "1") int pageNum,
                                               @RequestParam(defaultValue = "20") int pageSize,
                                               @RequestParam(required = false) String search) {
        return Result.ok(store.page(pageNum, pageSize, search));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:query')")
    public Result<AiApplicationStore.View> get(@PathVariable long id) {
        return Result.ok(store.get(id));
    }

    @GetMapping("/{id}/health")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:query')")
    public Result<AiApplicationService.Health> health(@PathVariable long id) {
        return Result.ok(service.health(id, identity.current()));
    }

    @GetMapping("/options/models")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:application:query') or hasAuthority('ai:application:add') or hasAuthority('ai:application:edit') or hasAuthority('ai:application:test'))")
    public Result<List<AiProviderRuntime.AvailableModel>> models(
            @RequestParam(defaultValue = "TEXT") OutputFormat outputFormat,
            @RequestParam(defaultValue = "true") boolean streaming) {
        var provider = providers.getIfUnique();
        if (provider == null) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        return Result.ok(provider.availableModels(new Requirements(outputFormat, streaming, false)));
    }

    @GetMapping("/options/keys")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:application:query') or hasAuthority('ai:application:add') or hasAuthority('ai:application:edit') or hasAuthority('ai:application:test'))")
    public Result<List<AiProviderRuntime.Target>> keys(@RequestParam String modelRef, @RequestParam String configVersion) {
        numericReference(modelRef); numericReference(configVersion);
        var provider = providers.getIfUnique();
        if (provider == null) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        InvocationControl control = new InvocationControl() {
            private final Instant deadline = Instant.now().plusSeconds(5);
            @Override public Instant deadline() { return deadline; }
            @Override public boolean cancelled() { return false; }
        };
        return Result.ok(provider.resolveTargets(new AiProviderRuntime.ModelBinding(new ModelVersionRef(modelRef, configVersion),
                AiProviderRuntime.KeyMode.AUTO, Set.of(), null, List.of()), Set.of(), control));
    }

    @GetMapping("/{id}/test-evidence")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:application:query') or hasAuthority('ai:application:test') or hasAuthority('ai:application:publish'))")
    public Result<List<AiApplicationService.EvidenceView>> testEvidence(@PathVariable long id) {
        return Result.ok(service.testEvidence(id));
    }

    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:add')")
    public Result<String> create(@RequestBody AiApplicationStore.Draft draft) {
        return Result.ok(store.create(draft, Long.parseLong(identity.current().subjectId())));
    }

    @PutMapping("/{id}/draft")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:edit')")
    public Result<AiApplicationStore.View> draft(@PathVariable long id, @RequestBody DraftChange change) {
        if (change.draft() == null) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        return Result.ok(store.updateDraft(id, revision(change.revision()), change.draft()));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:publish')")
    public Result<AiApplicationService.Version> publish(@PathVariable long id, @RequestBody Publish publish) {
        return Result.ok(service.publish(id, revision(publish.revision()), publish.testRequestIds(), identity.current()));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:query')")
    public Result<List<AiApplicationService.Version>> versions(@PathVariable long id) {
        return Result.ok(service.versions(id));
    }

    @PostMapping("/{id}/enabled")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:enable')")
    public Result<AiApplicationStore.View> enabled(@PathVariable long id, @RequestBody Enabled enabled) {
        if (enabled.enabled() == null) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        return Result.ok(service.setEnabled(id, revision(enabled.revision()), enabled.enabled(), identity.current()));
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:publish')")
    public Result<AiApplicationStore.View> rollback(@PathVariable long id, @RequestBody Rollback rollback) {
        if (rollback.appVersionId() == null || !rollback.appVersionId().matches("[1-9][0-9]{0,18}")) {
            throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        }
        return Result.ok(service.rollback(id, revision(rollback.revision()), Long.parseLong(rollback.appVersionId()), identity.current()));
    }

    @ModelAttribute
    public void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    @ExceptionHandler(AiRuntimeException.class)
    public ResponseEntity<Result<Void>> runtimeError(AiRuntimeException error) {
        int status = switch (error.code()) {
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN, RESOURCE_FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case VERSION_CONFLICT -> 409;
            case DEPENDENCY_UNAVAILABLE, INTERNAL_FAILURE -> 503;
            default -> 422;
        };
        return AiHttpFailures.response(status, error.code().name());
    }
    @ExceptionHandler(com.xinghuiTec.ai.scene.SceneFailure.class)
    public ResponseEntity<Result<Void>> sceneManaged(com.xinghuiTec.ai.scene.SceneFailure error){return AiHttpFailures.response(error.status(),error.getMessage());}

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() {
        return AiHttpFailures.response(403, "FORBIDDEN");
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> invalid() {
        return AiHttpFailures.response(400, "INVALID_INPUT");
    }

    private int revision(Integer value) {
        if (value == null || value < 0) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        return value;
    }

    private void numericReference(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        try { Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new AiRuntimeException(ErrorCode.INVALID_INPUT); }
    }
}
