package com.xinghuiTec.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.net.InetAddress;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenRouterModelTesterTest {
    AiModelConfigService models=mock(AiModelConfigService.class);
    AiModelCredentialService credentials=mock(AiModelCredentialService.class);
    StringRedisTemplate redis=mock(StringRedisTemplate.class);
    HttpClient client=mock(HttpClient.class);
    ValueOperations<String,String> values=mock(ValueOperations.class);
    OpenRouterModelTester tester;
    @BeforeEach void setup() throws Exception {
        when(models.get(1)).thenReturn(new AiModelConfigService.View(1,"openrouter","unit/model:free","https://openrouter.ai/api/v1",null,1,true,0,null,null,"DB",true));
        when(credentials.resolve(1)).thenReturn("unit-key");
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
        tester=new OpenRouterModelTester(models,credentials,redis,new ObjectMapper(),client,()->new InetAddress[]{InetAddress.getByName("8.8.8.8")});
    }
    void response(int code,String body) {
        HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
    }
    @Test void fixedEndpointSuccessRedactsKeyAndReturnsProviderId() {
        response(200,"{\"model\":\"unit/model\",\"id\":\"gen-unit\",\"choices\":[{\"message\":{\"content\":\"OK unit-key\"}}]}");
        var result=tester.test(1);
        assertEquals("SUCCESS",result.outcome());
        assertEquals("OK [redacted]",result.content());
        assertEquals("gen-unit",result.providerId());
        verify(client).sendAsync(argThat(request->request.uri().toString().equals(OpenRouterModelTester.ENDPOINT)&&request.method().equals("POST")&&request.timeout().orElseThrow().equals(Duration.ofSeconds(1))),any(HttpResponse.BodyHandler.class));
    }
    @Test void distinguishesProviderFailuresAndEmptyContent() {
        response(429,"");assertEquals("RATE_LIMITED",tester.test(1).outcome());
        response(401,"");assertEquals("AUTH_FAILED",tester.test(1).outcome());
        response(302,"");assertEquals("REDIRECT_REJECTED",tester.test(1).outcome());
        response(200,"{\"choices\":[]}");assertEquals("EMPTY_CONTENT",tester.test(1).outcome());
        response(200,"{\"model\":\"other\",\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");assertEquals("MODEL_MISMATCH",tester.test(1).outcome());
    }
    @Test void refusesDuplicateAndPrivateAddressBeforeSending() throws Exception {
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(false);
        assertThrows(ServiceException.class,()->tester.test(1));verifyNoInteractions(client);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
        tester=new OpenRouterModelTester(models,credentials,redis,new ObjectMapper(),client,()->new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        assertEquals("NETWORK_ERROR",tester.test(1).outcome());verifyNoInteractions(client);
    }
    @Test void timeoutCancelsOutstandingRequest() {
        CompletableFuture<HttpResponse<byte[]>> pending=new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        assertEquals("TIMEOUT",tester.test(1).outcome());assertTrue(pending.isCancelled());
    }
    @Test void explicitDeploymentProxyDelegatesDnsButKeepsFixedTarget() {
        assertEquals("127.0.0.1",OpenRouterModelTester.proxyUri("http://127.0.0.1:7897").getHost());
        assertThrows(ServiceException.class,()->OpenRouterModelTester.proxyUri("http://name:password@localhost:7897"));
        assertThrows(ServiceException.class,()->OpenRouterModelTester.proxyUri("http://localhost:7897/path"));
        response(200,"{\"model\":\"unit/model\",\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
        tester=new OpenRouterModelTester(models,credentials,redis,new ObjectMapper(),client,()->{throw new AssertionError("Local DNS must not resolve a CONNECT destination");},true);
        assertEquals("SUCCESS",tester.test(1).outcome());
        verify(client).sendAsync(argThat(request->request.uri().toString().equals(OpenRouterModelTester.ENDPOINT)),any(HttpResponse.BodyHandler.class));
    }
}
