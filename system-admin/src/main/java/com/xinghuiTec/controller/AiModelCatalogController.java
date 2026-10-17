package com.xinghuiTec.controller;

import com.xinghuiTec.ai.config.*;
import com.xinghuiTec.utils.Result;
import com.xinghuiTec.emues.ResultCodeEnum;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.xinghuiTec.ai.config.AiModelCatalogDtos.*;

/** No operation logging: scans use stored credentials, and responses are never cached. */
@RestController
@RequestMapping("/system/ai/model-catalog")
public class AiModelCatalogController {
    private static final ObjectMapper INPUT = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(8192).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AiModelCatalogService catalogs;

    public AiModelCatalogController(AiModelCatalogService catalogs) { this.catalogs = catalogs; }

    @ModelAttribute
    public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:query') or hasAuthority('ai:model:query'))")
    public Result<Page> catalog(@RequestParam(name="search", required=false) String search,
                                @RequestParam(name="pageNum", defaultValue="1") int pageNum,
                                @RequestParam(name="pageSize", defaultValue="20") int pageSize) {
        return Result.ok(catalogs.catalog(search, pageNum, pageSize));
    }

    @PostMapping("/scan")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:test') or hasAuthority('ai:model:test'))")
    public Result<Page> scan(@RequestBody Scan request) { return Result.ok(catalogs.scan(request)); }

    @PostMapping(value="/draft-scan", consumes="application/json")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:add') or hasAuthority('ai:config:edit') or hasAuthority('ai:config:test') or hasAuthority('ai:model:add') or hasAuthority('ai:model:edit') or hasAuthority('ai:model:test'))")
    public Result<Page> draftScan(HttpServletRequest request) {
        final DraftScan draft;
        try {
            byte[] body = request.getInputStream().readNBytes(16385);
            if (body.length > 16384) throw new AiModelCatalogException(413, "REQUEST_LIMIT");
            draft = INPUT.readValue(body, DraftScan.class);
        } catch (AiModelCatalogException safe) { throw safe; }
        catch (Exception invalid) { throw new AiModelCatalogException(400, "INVALID_INPUT"); }
        return Result.ok(catalogs.draftScan(draft));
    }

    @ExceptionHandler(AiModelCatalogException.class)
    public ResponseEntity<Result<Void>> failure(AiModelCatalogException error) {
        return ResponseEntity.status(error.status()).body(Result.fail(ResultCodeEnum.PARAM_ERROR.getCode(), error.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> forbidden() {
        return ResponseEntity.status(403).body(Result.fail(ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN.getCode(), "AI_MODEL_CATALOG:FORBIDDEN"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> internal(Exception error) {
        if (error instanceof org.springframework.web.HttpMediaTypeNotSupportedException)
            return ResponseEntity.status(415).body(Result.fail(ResultCodeEnum.PARAM_ERROR.getCode(), "AI_MODEL_CATALOG:INVALID_INPUT"));
        boolean invalid = error instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || error instanceof org.springframework.beans.TypeMismatchException
                || error instanceof org.springframework.web.bind.ServletRequestBindingException;
        return ResponseEntity.status(invalid ? 400 : 503).body(Result.fail(ResultCodeEnum.PARAM_ERROR.getCode(),
                invalid ? "AI_MODEL_CATALOG:INVALID_INPUT" : "AI_MODEL_CATALOG:UNAVAILABLE"));
    }
}
