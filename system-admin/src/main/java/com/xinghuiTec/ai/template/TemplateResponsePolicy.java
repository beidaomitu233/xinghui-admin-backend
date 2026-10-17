package com.xinghuiTec.ai.template;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import com.xinghuiTec.utils.Result;
import com.xinghuiTec.emues.ResultCodeEnum;
import com.xinghuiTec.exception.ServiceException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Admin definitions/defaults/previews are private, including error responses. */
@RestControllerAdvice(assignableTypes = {TemplateAdminController.class, DataResourceAdminController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TemplateResponsePolicy implements ResponseBodyAdvice<Object> {
    private ResponseEntity<Result<Void>> failure(int status, ResultCodeEnum code, String message) {
        var result = Result.<Void>fail(code); result.setMessage(message);
        // Advice-originated responses do not match the controller-only ResponseBodyAdvice selector.
        return ResponseEntity.status(status).header("Cache-Control", "no-store, private").header("Pragma", "no-cache").body(result);
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<Result<Void>> invalid() { return failure(400, ResultCodeEnum.PARAM_ERROR, "INVALID_INPUT"); }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> media() { return failure(415, ResultCodeEnum.PARAM_ERROR, "INVALID_INPUT"); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> size() { return failure(413, ResultCodeEnum.PARAM_ERROR, "INVALID_INPUT"); }
    @ExceptionHandler(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<Result<Void>> unauthenticated() { return failure(401, ResultCodeEnum.ADMIN_ACCOUNT_NOT_LOGIN_ERROR, "UNAUTHENTICATED"); }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() { return failure(403, ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN, "FORBIDDEN"); }
    @ExceptionHandler(com.xinghuiTec.ai.contract.AiRuntimeException.class)
    public ResponseEntity<Result<Void>> runtime(com.xinghuiTec.ai.contract.AiRuntimeException exception) {
        return failure(422, ResultCodeEnum.PARAM_ERROR, exception.code().name());
    }
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Result<Void>> service(ServiceException exception) {
        if (Integer.valueOf(ResultCodeEnum.FAIL.getCode()).equals(exception.getCode())) return failure(409, ResultCodeEnum.FAIL, "SCENE_MANAGED".equals(exception.getMessage())?"SCENE_MANAGED":"VERSION_CONFLICT");
        if (Integer.valueOf(ResultCodeEnum.DATA_ERROR.getCode()).equals(exception.getCode())) return failure(404, ResultCodeEnum.DATA_ERROR, "NOT_FOUND");
        if (Integer.valueOf(ResultCodeEnum.SERVICE_ERROR.getCode()).equals(exception.getCode())) return failure(503, ResultCodeEnum.SERVICE_ERROR, "DEPENDENCY_UNAVAILABLE");
        return failure(422, ResultCodeEnum.PARAM_ERROR, "INVALID_INPUT");
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Result<Void>> database() { return failure(503, ResultCodeEnum.SERVICE_ERROR, "DEPENDENCY_UNAVAILABLE"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected() { return failure(500, ResultCodeEnum.SERVICE_ERROR, "INTERNAL_FAILURE"); }
    public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType type,
                                  Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        response.getHeaders().set("Cache-Control", "no-store, private");
        response.getHeaders().set("Pragma", "no-cache");
        return body;
    }
}
