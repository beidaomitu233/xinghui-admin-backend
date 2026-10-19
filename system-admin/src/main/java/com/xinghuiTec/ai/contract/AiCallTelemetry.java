package com.xinghuiTec.ai.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/** One upstream interaction. Text fields are bounded and scrubbed before construction; never outbound messages or credentials. */
public record AiCallTelemetry(int schemaVersion,boolean streaming,Instant startedAt,Instant finishedAt,Integer httpStatus,
        Long elapsedMs,Long responseHeadersMs,Long firstByteMs,Long firstOutputMs,Long firstTextMs,Long lastOutputMs,
        String firstOutputKind,String returnedModel,String finishReason,
        Long inputTokens,Long outputTokens,Long totalTokens,Long cacheReadTokens,Long cacheWriteTokens,Long reasoningTokens,
        BigDecimal reportedCost,String currency,String usageJson,String responseHeadersJson,String responseBody,
        String responseFormat,String captureStatus,String captureReason,Long receivedBytes,Long archivedBytes,
        boolean redacted,boolean truncated) {
    public static final int SCHEMA_VERSION=1;
    public static final int MAX_ARCHIVE_BYTES=262144;
    public AiCallTelemetry {
        if(schemaVersion!=SCHEMA_VERSION || (httpStatus!=null&&(httpStatus<100||httpStatus>599))
                || !Set.of("COMPLETE","PARTIAL","NOT_CAPTURED","FAILED").contains(captureStatus)
                || (responseFormat!=null&&!Set.of("JSON","SSE","TEXT").contains(responseFormat)))throw new IllegalArgumentException("Invalid telemetry classification");
        for(Long value:new Long[]{elapsedMs,responseHeadersMs,firstByteMs,firstOutputMs,firstTextMs,lastOutputMs,inputTokens,
                outputTokens,totalTokens,cacheReadTokens,cacheWriteTokens,reasoningTokens,receivedBytes,archivedBytes})
            if(value!=null&&value<0)throw new IllegalArgumentException("Invalid telemetry count");
        if(!streaming&&(firstOutputMs!=null||firstTextMs!=null||lastOutputMs!=null||firstOutputKind!=null))throw new IllegalArgumentException("Nonstream output timing is not measurable");
        long bytes=0;for(String text:new String[]{usageJson,responseHeadersJson,responseBody})if(text!=null)bytes+=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(bytes>MAX_ARCHIVE_BYTES || (archivedBytes!=null&&archivedBytes!=bytes))throw new IllegalArgumentException("Invalid telemetry archive bounds");
    }
    @Override public String toString(){return "AiCallTelemetry[REDACTED]";}
}
