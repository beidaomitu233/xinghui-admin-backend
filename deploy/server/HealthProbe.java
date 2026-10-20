import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Existing public endpoint only. No login, model, tool, write or invented Actuator endpoint. */
public final class HealthProbe {
    public static void main(String[] args) throws Exception {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:17799/auth/wechat/capability"))
                .timeout(Duration.ofSeconds(4)).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        String body=response.body();
        if(response.statusCode()!=200 || body.length()>8192 || !body.matches("(?s).*\\\"code\\\"\\s*:\\s*200\\b.*")
                || body.contains("CONFIG_UNAVAILABLE"))System.exit(1);
    }
}
