package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** Redis TTL is a temporary output buffer, never a durable prompt/output audit store. */
@Component
public class RedisAiRuntimeEvents implements AiRuntimeEvents {
    private static final DefaultRedisScript<Long> APPEND = new DefaultRedisScript<>("""
            local value=cjson.decode(ARGV[1])
            if redis.call('EXISTS',KEYS[4])==1 then return -3 end
            local terminal=value.name=='COMPLETED' or value.name=='FAILED' or value.name=='CANCELLED'
            if terminal then redis.call('SET',KEYS[4],'1','EX',ARGV[2]) end
            if redis.call('LLEN',KEYS[1]) >= tonumber(ARGV[3]) then return -2 end
            local n=(tonumber(redis.call('GET',KEYS[2])) or 0)+1
            value.sequence=n
            local encoded=cjson.encode(value)
            local total=tonumber(redis.call('GET',KEYS[3])) or 0
            if #encoded > tonumber(ARGV[4]) or total+#encoded > tonumber(ARGV[5]) then return -1 end
            redis.call('SET',KEYS[2],n); redis.call('SET',KEYS[3],total+#encoded)
            redis.call('RPUSH',KEYS[1],encoded)
            for i=1,3 do redis.call('EXPIRE',KEYS[i],ARGV[2]) end
            return n
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String prefix;

    public RedisAiRuntimeEvents(StringRedisTemplate redis, ObjectMapper mapper,
                                @Value("${ai.runtime.redis-prefix:ai:v04:runtime:}") String prefix) {
        if (prefix == null || !prefix.matches("[A-Za-z0-9:_-]{1,80}:")) {
            throw new IllegalArgumentException("Invalid AI event namespace");
        }
        this.redis = redis;
        this.mapper = mapper;
        this.prefix = prefix;
    }

    @Override
    public Event append(Event event) {
        String key = key(event.requestId());
        try {
            if (event.name() == Name.ACCEPTED) requireVolatileInstance();
            Long sequence = redis.execute(APPEND, List.of(key + ":events", key + ":sequence", key + ":bytes", key + ":terminal"),
                    mapper.writeValueAsString(event.sequenced(0)), "600", "2048", "32768", "262144");
            if (sequence != null && sequence == -3) throw new AiRuntimeException(ErrorCode.CANCELLED);
            if (sequence == null || sequence < 1) throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
            return event.sequenced(sequence);
        } catch (JsonProcessingException invalid) {
            throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE);
        } catch (AiRuntimeException safe) {
            throw safe;
        } catch (RuntimeException unavailable) {
            // Redis exceptions may contain arguments. Never propagate or log their message/body.
            throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public List<Event> after(String requestId, long sequence) {
        if (sequence < 0 || sequence > 2048) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        try {
            List<String> rows = redis.opsForList().range(key(requestId) + ":events", sequence, sequence + 99);
            if (rows == null) return List.of();
            var result = new java.util.ArrayList<Event>();
            for (String row : rows) {
                if (row.length() > 32_768) throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE);
                Event event = mapper.readValue(row, Event.class);
                if (!event.requestId().equals(requestId) || event.sequence() <= sequence) {
                    throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE);
                }
                result.add(event);
            }
            return List.copyOf(result);
        } catch (JsonProcessingException invalid) {
            throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE);
        } catch (AiRuntimeException safe) {
            throw safe;
        } catch (RuntimeException unavailable) {
            throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private String key(String requestId) {
        AiApplicationService.positiveId(requestId);
        return prefix + "{" + requestId + "}";
    }

    private void requireVolatileInstance() {
        Boolean volatileOnly = redis.execute((RedisCallback<Boolean>) connection -> {
            var aof = connection.serverCommands().getConfig("appendonly");
            var rdb = connection.serverCommands().getConfig("save");
            return aof != null && rdb != null && "no".equalsIgnoreCase(aof.getProperty("appendonly"))
                    && rdb.getProperty("save", "missing").isBlank();
        });
        if (!Boolean.TRUE.equals(volatileOnly)) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
