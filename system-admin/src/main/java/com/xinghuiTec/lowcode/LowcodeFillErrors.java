package com.xinghuiTec.lowcode;

import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.utils.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {LowcodeFillController.class, LowcodeSharingController.class})
public class LowcodeFillErrors {
    @ExceptionHandler(LowcodeSharingService.Failure.class)
    public ResponseEntity<Result<Void>> failure(LowcodeSharingService.Failure failure) {
        return ResponseEntity.status(failure.status).body(Result.fail(failure.status, failure.getMessage()));
    }
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Result<Void>> invalid(ServiceException failure) {
        return ResponseEntity.badRequest().body(Result.fail(400, failure.getMessage()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() {
        return ResponseEntity.status(403).body(Result.fail(403, "没有此操作权限"));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> malformed() {
        return ResponseEntity.badRequest().body(Result.fail(400, "请求包含未定义字段或数据格式不正确"));
    }
}
