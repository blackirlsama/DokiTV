import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Tiny probe without curl, a shell expansion, credentials, or response-body logging. */
public final class HealthProbe {
    public static void main(String[] args) {
        try {
            URI uri = URI.create(args[0]);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())) System.exit(1);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
            System.exit(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 ? 0 : 1);
        } catch (Exception e) { System.exit(1); }
    }
}
