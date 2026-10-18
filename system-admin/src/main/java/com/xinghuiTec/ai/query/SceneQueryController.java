package com.xinghuiTec.ai.query;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

/** Authenticated business scopes; unlike the legacy directory this entry also admits tenant callers. */
@RestController
@RequestMapping("/system/ai/scene-data/sql")
@PreAuthorize("hasAnyAuthority('ai:scene:query','ai:scene:add','ai:scene:edit','ai:scene:test','ai:scene:invoke')")
public class SceneQueryController {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(16000).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final AiSceneQueryRuntime data;
    private final AiInvocationIdentity identity;
    public SceneQueryController(AiSceneQueryRuntime data, AiInvocationIdentity identity) { this.data = data; this.identity = identity; }
    public record QueryRequest(List<String> datasetKeys, BusinessRef businessRef, SqlQuery query) {
        public QueryRequest { if (query == null) throw new IllegalArgumentException("Missing query"); }
        QueryContext context() { return new QueryContext(datasetKeys, businessRef); }
    }
    @GetMapping("/catalog") public Result<Page<DatasetOption>> catalog(@RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize) {
        return Result.ok(data.catalog(new OptionQuery(keyword, pageNum, pageSize), identity.current(), control()));
    }
    @PostMapping("/schema") public Result<AuthorizedSchema> schema(HttpServletRequest request) {
        return Result.ok(data.authorizedSchema(identity.current(), body(request, QueryContext.class), control()));
    }
    @PostMapping("/validate") public Result<SqlValidation> validate(HttpServletRequest request) {
        var input = body(request, QueryRequest.class);
        return Result.ok(data.validateGeneratedSql(input.query(), identity.current(), input.context(), control()));
    }
    @PostMapping("/preview") public Result<QueryResult> preview(HttpServletRequest request) {
        var input = body(request, QueryRequest.class);
        return Result.ok(data.preview(input.query(), identity.current(), input.context(), control()));
    }
    private <T> T body(HttpServletRequest request, Class<T> type) {
        if (request.getContentType() == null || !request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json")
                || request.getHeader("Content-Encoding") != null && !request.getHeader("Content-Encoding").equalsIgnoreCase("identity")) throw new BodyFailure(415);
        try {
            byte[] bytes = request.getInputStream().readNBytes(65537);
            if (bytes.length > 65536) throw new BodyFailure(413);
            T value = JSON.readValue(bytes, type);
            if (value == null) throw new IllegalArgumentException("Missing body");
            return value;
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid body"); }
    }
    private static final class BodyFailure extends RuntimeException {
        final int status; BodyFailure(int status) { super("INVALID_CONTENT"); this.status = status; }
    }
    @ModelAttribute public void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private"); response.setHeader("Pragma", "no-cache"); response.setHeader("X-Content-Type-Options", "nosniff");
    }
    @ExceptionHandler(SqlQueryException.class) public ResponseEntity<Result<Void>> queryFailure(SqlQueryException failure) {
        return AiHttpFailures.response(failure.status(), "AI_QUERY_" + failure.issue().name());
    }
    @ExceptionHandler(BodyFailure.class) public ResponseEntity<Result<Void>> content(BodyFailure failure) { return AiHttpFailures.response(failure.status, "INVALID_CONTENT"); }
    @ExceptionHandler(AiRuntimeException.class) public ResponseEntity<Result<Void>> runtime(AiRuntimeException failure) {
        int status = switch (failure.code()) { case UNAUTHENTICATED -> 401; case FORBIDDEN, RESOURCE_FORBIDDEN -> 403;
            case INVALID_INPUT -> 400; case RATE_LIMITED -> 429; case DEADLINE_EXCEEDED, RESOURCE_TIMEOUT -> 504; default -> 503; };
        return AiHttpFailures.response(status, failure.code().name());
    }
    @PreAuthorize("permitAll()")
    @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<Result<Void>> denied() { return AiHttpFailures.response(403, "FORBIDDEN"); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> invalid() { return AiHttpFailures.response(400, "INVALID_INPUT"); }
    @ExceptionHandler(Exception.class) public ResponseEntity<Result<Void>> unavailable() { return AiHttpFailures.response(503, "DEPENDENCY_UNAVAILABLE"); }
    private InvocationControl control() {
        var deadline = Instant.now().plusSeconds(10);
        return new InvocationControl() { public Instant deadline() { return deadline; } public boolean cancelled() { return Thread.currentThread().isInterrupted(); } };
    }
}
