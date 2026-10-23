package com.xinghuiTec.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Only the documented HTTPS token endpoint is used. No redirects or arbitrary provider URLs. */
@Component
public class WechatIdentityClient {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    public WechatIdentityClient(ObjectMapper json) { this.json=json; }
    static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    public String exchange(String client,String secret,String code,String expectedScope) {
        if(code==null || !code.matches("[A-Za-z0-9_-]{1,256}")) throw new WechatFailure(400,"微信授权票据无效");
        try {
            URI endpoint=URI.create("https://api.weixin.qq.com/sns/oauth2/access_token?appid="+encode(client)+"&secret="+encode(secret)+"&code="+encode(code)+"&grant_type=authorization_code");
            var response=http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try(var stream=response.body()) { body=stream.readNBytes(16385); }
            if(response.statusCode()!=200 || body.length>16384) throw new IllegalArgumentException();
            var value=json.readTree(body);
            String openid=value.path("openid").asText("");
            String scope=value.path("scope").asText("");
            if(value.has("errcode") || value.path("is_snapshotuser").asInt(0)==1 || value.path("access_token").asText("").isBlank()
                    || !openid.matches("[A-Za-z0-9_-]{1,128}") || !java.util.Arrays.asList(scope.split(",")).contains(expectedScope)) throw new IllegalArgumentException();
            return openid;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new WechatFailure(503,"微信身份验证暂时不可用，请重新发起授权");
        } catch(Exception unavailable) {
            // Never propagate a URL, response body, AppSecret, code or access_token to logs/API.
            throw new WechatFailure(503,"微信身份验证失败，请重新发起授权");
        }
    }
}
