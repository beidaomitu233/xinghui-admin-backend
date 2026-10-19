package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Unknown upstream extensions stay exact without expanding an untrusted decimal exponent. */
final class AiCallLogArchiveJson {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(262144).maxNumberLength(1024).build()).build())
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private AiCallLogArchiveJson(){}
    static Map<String,Object> object(String value){
        if(value==null)return null;
        if(value.getBytes(StandardCharsets.UTF_8).length>262144)throw new IllegalStateException("AI_CALL_ARCHIVE_INVALID");
        try{
            Map<String,Object> parsed=JSON.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});
            @SuppressWarnings("unchecked") Map<String,Object> exact=(Map<String,Object>)exact(parsed);return exact;
        }catch(Exception invalid){throw new IllegalStateException("AI_CALL_ARCHIVE_INVALID");}
    }
    private static Object exact(Object value){
        if(value instanceof BigInteger integer)return integer.bitLength()<64?integer.longValue():integer.toString();
        // String preserves arbitrary precision and scientific notation, including large exponents.
        if(value instanceof BigDecimal decimal)return decimal.toString();
        if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();map.forEach((key,item)->result.put((String)key,exact(item)));return result;}
        if(value instanceof List<?> list)return list.stream().map(AiCallLogArchiveJson::exact).toList();
        return value;
    }
}
