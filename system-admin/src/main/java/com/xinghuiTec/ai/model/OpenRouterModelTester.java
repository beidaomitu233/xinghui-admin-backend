package com.xinghuiTec.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.exception.ServiceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

@Service
public class OpenRouterModelTester {
    public static final String ENDPOINT="https://openrouter.ai/api/v1/chat/completions";
    private final AiModelConfigService models;
    private final AiModelCredentialService credentials;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final HttpClient client;
    private final AddressResolver addresses;
    private final boolean trustedProxy;
    interface AddressResolver { InetAddress[] resolve() throws java.net.UnknownHostException; }
    @Autowired
    public OpenRouterModelTester(AiModelConfigService models,AiModelCredentialService credentials,StringRedisTemplate redis,ObjectMapper json) {
        this(models,credentials,redis,json,deploymentClient(System.getenv("OPENROUTER_HTTP_PROXY")),()->InetAddress.getAllByName("openrouter.ai"),proxyUri(System.getenv("OPENROUTER_HTTP_PROXY"))!=null);
    }
    OpenRouterModelTester(AiModelConfigService models,AiModelCredentialService credentials,StringRedisTemplate redis,ObjectMapper json,HttpClient client,AddressResolver addresses) {
        this(models,credentials,redis,json,client,addresses,false);
    }
    OpenRouterModelTester(AiModelConfigService models,AiModelCredentialService credentials,StringRedisTemplate redis,ObjectMapper json,HttpClient client,AddressResolver addresses,boolean trustedProxy) {
        this.models=models;this.credentials=credentials;this.redis=redis;this.json=json;this.client=client;this.addresses=addresses;this.trustedProxy=trustedProxy;
    }
    static URI proxyUri(String value) {
        if(value==null || value.isBlank()) return null;
        try {
            var uri=URI.create(value.trim());
            if(!"http".equals(uri.getScheme()) || uri.getHost()==null || uri.getPort()<1 || uri.getPort()>65535 || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !uri.getPath().isEmpty()) throw new IllegalArgumentException();
            return uri;
        } catch(RuntimeException invalid) { throw new ServiceException("部署代理配置无效，请检查 OPENROUTER_HTTP_PROXY"); }
    }
    private static HttpClient deploymentClient(String value) {
        var builder=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10));
        var proxy=proxyUri(value);
        if(proxy!=null) builder.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(proxy.getHost(),proxy.getPort())));
        else builder.proxy(new java.net.ProxySelector() {
            public java.util.List<java.net.Proxy> select(URI uri) { return List.of(java.net.Proxy.NO_PROXY); }
            public void connectFailed(URI uri,java.net.SocketAddress address,java.io.IOException failure) {}
        });
        return builder.build();
    }
    public record Outcome(String outcome,String model,String returnedModel,long elapsedMs,Integer httpStatus,String content,String providerId,String message) {}
    public Outcome test(long id) {
        var model=models.get(id);
        if(!model.enabled()) throw new ServiceException("请先启用模型");
        if(!"openrouter".equals(model.providerCode()) || !List.of("https://openrouter.ai/api/v1",ENDPOINT).contains(model.endpointUrl()))
            throw new ServiceException("测试仅支持官方 OpenRouter 端点");
        String key=credentials.resolve(id),lock="admin:cfg:model:test:"+id,owner=UUID.randomUUID().toString();
        try {
            if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock,owner,Duration.ofSeconds(75))))
                throw new ServiceException("该模型正在测试，请稍后重试");
        } catch (ServiceException busy) { throw busy; }
        catch (RuntimeException unavailable) { throw new ServiceException("测试并发保护不可用，请稍后重试"); }
        long start=System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            // An explicitly configured deployment CONNECT proxy resolves the fixed HTTPS host.
            // Direct mode still rejects private/reserved DNS answers before any request.
            if(!trustedProxy) {
                var resolved=addresses.resolve();
                if(resolved.length==0) throw new java.net.UnknownHostException();
                for(var address:resolved) {
                    String literal=address.getHostAddress();
                    new AiModelConfigRules().endpointUrl("https://"+(literal.contains(":")?"["+literal+"]":literal));
                }
            }
            int timeout=Math.min(60,model.timeoutSeconds());
            String body=json.writeValueAsString(Map.of("model",model.modelCode(),"messages",List.of(Map.of("role","user","content","Reply with exactly OK.")),"max_tokens",256,"stream",false));
            var request=HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(Duration.ofSeconds(timeout))
                    .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofByteArray());
            var response=pending.get(timeout,TimeUnit.SECONDS);
            int status=response.statusCode();
            if(status!=200) return result(status==429?"RATE_LIMITED":status==401||status==403?"AUTH_FAILED":status>=300&&status<400?"REDIRECT_REJECTED":"PROVIDER_ERROR",model.modelCode(),null,start,status,null,null,"供应方返回 HTTP "+status);
            if(response.body().length>65536) return result("INVALID_RESPONSE",model.modelCode(),null,start,status,null,null,"供应方响应过大");
            var data=json.readTree(response.body());
            if(data.has("error")) return result("PROVIDER_ERROR",model.modelCode(),null,start,status,null,null,"供应方返回业务错误");
            String returned=data.path("model").asText("");
            String content=data.path("choices").path(0).path("message").path("content").asText("");
            if(content.isBlank()) return result("EMPTY_CONTENT",model.modelCode(),returned,start,status,null,safeId(data.path("id").asText()),"供应方未返回文本内容");
            if(!returned.replace(":free","").equals(model.modelCode().replace(":free","")))
                return result("MODEL_MISMATCH",model.modelCode(),returned,start,status,null,null,"供应方返回的模型与配置不一致");
            content=content.replace(key,"[redacted]");
            return result("SUCCESS",model.modelCode(),returned,start,status,content.substring(0,Math.min(content.length(),2000)),safeId(data.path("id").asText()),"测试成功");
        } catch (java.util.concurrent.TimeoutException timeout) { return result("TIMEOUT",model.modelCode(),null,start,null,null,null,"供应方请求超时"); }
        catch (java.util.concurrent.ExecutionException failed) {
            return result(failed.getCause() instanceof java.net.http.HttpTimeoutException?"TIMEOUT":"NETWORK_ERROR",model.modelCode(),null,start,null,null,null,"供应方网络请求未完成");
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt();return result("INTERRUPTED",model.modelCode(),null,start,null,null,null,"测试已中断"); }
        catch (Exception failure) { return result("NETWORK_ERROR",model.modelCode(),null,start,null,null,null,"供应方网络或响应不可用"); }
        finally {
            if(pending!=null && !pending.isDone()) pending.cancel(true);
            try { redis.execute(new DefaultRedisScript<>("if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Long.class),List.of(lock),owner); }
            catch (RuntimeException unavailable) { /* Only our expiring lock remains; no broad cleanup. */ }
        }
    }
    private String safeId(String value) { return value!=null && value.matches("[A-Za-z0-9._:-]{1,128}")?value:null; }
    private Outcome result(String state,String model,String returned,long start,Integer status,String content,String providerId,String message) {
        return new Outcome(state,model,returned,(System.nanoTime()-start)/1_000_000,status,content,providerId,message);
    }
}
