package com.xinghuiTec.ai.application;

import com.xinghuiTec.emues.ResultCodeEnum;
import com.xinghuiTec.utils.Result;
import org.springframework.http.ResponseEntity;

/** Local to B AI controllers: HTTP status is distinct from the established Result business code. */
public final class AiHttpFailures {
    private AiHttpFailures() {}

    public static ResponseEntity<Result<Void>> response(int status, String safeCode) {
        ResultCodeEnum code = switch (status) {
            case 400, 413, 415, 422 -> ResultCodeEnum.PARAM_ERROR;
            case 401 -> ResultCodeEnum.ADMIN_ACCOUNT_NOT_LOGIN_ERROR;
            case 403 -> ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN;
            case 404 -> ResultCodeEnum.DATA_ERROR;
            case 409 -> ResultCodeEnum.FAIL;
            default -> ResultCodeEnum.SERVICE_ERROR;
        };
        Result<Void> body = Result.fail(code);
        body.setMessage(safeCode);
        return ResponseEntity.status(status).body(body);
    }
}
