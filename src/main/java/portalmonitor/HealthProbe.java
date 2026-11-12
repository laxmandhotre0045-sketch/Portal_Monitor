package portalmonitor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Container healthcheck: exits 0 only when the server answers 200 on its readiness URL.
 *
 *   java -cp /app/portal-monitor.jar portalmonitor.HealthProbe [url]
 *
 * Default URL: http://127.0.0.1:8085/ready. Needs no curl in the image and reads no
 * configuration, so it cannot leak or depend on secrets. Prints the response body so
 * `docker inspect --format '{{json .State.Health}}' portal-monitor` shows why.
 */
public final class HealthProbe {

    static final String DEFAULT_URL = "http://127.0.0.1:8085/ready";

    private HealthProbe() { }

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : DEFAULT_URL;
        System.exit(probe(url, Duration.ofSeconds(12)));
    }

    /** 0 = healthy, 1 = answered but not ready, 2 = no answer at all. */
    static int probe(String url, Duration timeout) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("HTTP " + resp.statusCode() + " " + Text.clean(resp.body(), 1500));
            return resp.statusCode() == 200 ? 0 : 1;
        } catch (Exception e) {
            System.out.println("No answer from " + url + ": " + HttpChecker.describe(e));
            return 2;
        }
    }
}
