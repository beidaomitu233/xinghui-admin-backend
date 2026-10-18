package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.AiHttpFailures;
import com.xinghuiTec.ai.application.AiRuntimeService;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/** Applies to B scene endpoints only; global business Result and authentication handling remain intact. */
@RestControllerAdvice(basePackages={"com.xinghuiTec.ai.scene","com.xinghuiTec.ai.conversation","com.xinghuiTec.ai.modelhub"})
public class SceneHttpErrors {
    @ExceptionHandler(com.xinghuiTec.ai.conversation.ConversationFailure.class) public ResponseEntity<Result<?>> conversation(com.xinghuiTec.ai.conversation.ConversationFailure error){
        var original=AiHttpFailures.response(error.status(),error.getMessage());
        Result<com.xinghuiTec.ai.conversation.ConversationDtos.ErrorDetails> result=Result.fail(original.getBody().getCode(),error.getMessage());result.setData(error.details());
        return ResponseEntity.status(error.status()).body(result);
    }
    @ExceptionHandler(SceneFailure.class) public ResponseEntity<Result<?>> scene(SceneFailure error){
        var original=AiHttpFailures.response(error.status(),error.getMessage());
        if(error.details()==null)return ResponseEntity.status(error.status()).body(original.getBody());
        Result<SceneDtos.ErrorDetails> result=Result.fail(original.getBody().getCode(),error.getMessage());result.setData(error.details());
        return ResponseEntity.status(error.status()).body(result);
    }
    @ExceptionHandler(AiRuntimeService.AdmissionFailure.class) public ResponseEntity<?> admission(AiRuntimeService.AdmissionFailure error,HttpServletResponse response){response.setHeader("X-Request-ID",error.requestId());return runtime(new AiRuntimeException(error.code()));}
    @ExceptionHandler(AiRuntimeException.class) public ResponseEntity<?> runtime(AiRuntimeException error){
        int status=switch(error.code()){case UNAUTHENTICATED->401;case FORBIDDEN,RESOURCE_FORBIDDEN->403;case NOT_FOUND->404;
            case VERSION_CONFLICT,MODEL_UNAVAILABLE,NO_USABLE_KEY,TEMPLATE_UNAVAILABLE,RESOURCE_UNAVAILABLE->409;
            case INVALID_INPUT->400;case MISSING_VARIABLE,CAPABILITY_UNSUPPORTED->422;case RESOURCE_TIMEOUT,DEADLINE_EXCEEDED->504;case RATE_LIMITED->429;default->503;};
        return AiHttpFailures.response(status,error.code().name());
    }
    @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<?> denied(){return AiHttpFailures.response(403,"FORBIDDEN");}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) public ResponseEntity<?> invalid(){return AiHttpFailures.response(400,"INVALID_INPUT");}
}
