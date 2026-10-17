package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import org.springframework.stereotype.Component;

/** Explicit JSON envelope: rejects identity additions, duplicate keys, trailing values and executable objects. */
@Component
public class AiRuntimeBodyCodec {
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(8192).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final ObjectMapper configurationMapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(32000).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    public <T>T readSceneConfiguration(byte[] bytes,Class<T> type){
        if(bytes==null || bytes.length==0 || bytes.length>131072)throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        try{T value=configurationMapper.readValue(bytes,type);if(value==null)throw new IllegalArgumentException();return value;}
        catch(Exception invalid){throw new AiRuntimeException(ErrorCode.INVALID_INPUT);}
    }

    public <T> T read(byte[] bytes, Class<T> type) {
        if (bytes == null || bytes.length == 0 || bytes.length > AiRuntimeBoundaryFilter.MAX_BODY_BYTES) {
            throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        }
        try {
            T result = mapper.readValue(bytes, type);
            if (result == null) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            return result;
        }
        catch (Exception invalid) { throw new AiRuntimeException(ErrorCode.INVALID_INPUT); }
    }
}
