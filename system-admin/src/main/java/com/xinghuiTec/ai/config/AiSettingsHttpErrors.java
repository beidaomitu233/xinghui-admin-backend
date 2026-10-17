package com.xinghuiTec.ai.config;

import com.xinghuiTec.ai.application.AiHttpFailures;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackageClasses = AiSettingsController.class)
public class AiSettingsHttpErrors {
    @ExceptionHandler(AiConfigurationException.class)
    public ResponseEntity<?> configuration(AiConfigurationException failure) {
        return AiHttpFailures.response(failure.status(), failure.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> denied() {
        return AiHttpFailures.response(403, "FORBIDDEN");
    }

    @ExceptionHandler(AiRuntimeException.class)
    public ResponseEntity<?> runtime(AiRuntimeException failure) {
        int status = failure.code() == com.xinghuiTec.ai.contract.AiTypes.ErrorCode.UNAUTHENTICATED ? 401 : 403;
        return AiHttpFailures.response(status, failure.code().name());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> safe(Exception failure) {
        boolean input = failure instanceof IllegalArgumentException
                || failure instanceof com.xinghuiTec.exception.ServiceException
                || failure instanceof org.springframework.validation.BindException
                || failure instanceof org.springframework.beans.TypeMismatchException
                || failure instanceof org.springframework.http.converter.HttpMessageNotReadableException;
        return AiHttpFailures.response(input ? 400 : 503, input ? "INVALID_INPUT" : "DEPENDENCY_UNAVAILABLE");
    }
}
