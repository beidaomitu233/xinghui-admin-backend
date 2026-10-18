package com.xinghuiTec.ai.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.util.HexFormat;

final class SqlFingerprints {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private SqlFingerprints() {}
    static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value))); }
        catch (Exception invalid) { throw new SqlQueryException(com.xinghuiTec.ai.contract.AiSceneQueryRuntime.QueryIssue.INVALID_SQL); }
    }
}
