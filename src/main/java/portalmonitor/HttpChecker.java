package portalmonitor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Runs every HTTP check in parallel and turns the outcome into a CheckResult. */
public final class HttpChecker {

    private final Config cfg;
    private final HttpClient client;

    public HttpChecker(Config cfg) {
        this.cfg = cfg;
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(cfg.connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER);   // a redirect is not a healthy answer
        if (!cfg.verifySsl) {
            // Opt-in only: accepts self-signed / mismatched certificates.
            // Must be set before the client is built.
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            b.sslContext(trustAllContext());
            Log.info("check.verifySsl=false - HTTPS certificates are NOT verified.");
        }
        this.client = b.build();
    }

    /** Fires all requests at once and returns one result per portal id. */
    public Map<Integer, CheckResult> checkAll(List<Portal> portals) {
        Map<Integer, CompletableFuture<CheckResult>> futures = new HashMap<>();
        for (Portal p : portals) {
            futures.put(p.id(), checkOne(p.healthUrl()));
        }
        Map<Integer, CheckResult> results = new HashMap<>();
        futures.forEach((id, f) -> {
            try {
                results.put(id, f.join());
            } catch (Exception e) {
                // checkOne handles its own failures; this is a belt-and-braces guard so one
                // broken URL can never abort the whole cycle.
                results.put(id, CheckResult.down(describe(e)));
            }
        });
        return results;
    }

    private CompletableFuture<CheckResult> checkOne(String url) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url == null ? "" : url.trim()))
                    .timeout(Duration.ofSeconds(cfg.requestTimeoutSeconds))
                    .header("User-Agent", "PortalMonitor/2.0")
                    .header("Accept", "application/json, text/html, text/plain, */*")
                    .GET()
                    .build();
        } catch (IllegalArgumentException | NullPointerException e) {
            return CompletableFuture.completedFuture(
                    CheckResult.down("Invalid health_url: " + url));
        }

        long start = System.nanoTime();
        return client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .handle((resp, ex) -> {
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    if (ex == null) {
                        int code = resp.statusCode();
                        boolean up = cfg.successCodes.contains(code);
                        return new CheckResult(
                                up ? CheckResult.Outcome.UP : CheckResult.Outcome.DOWN,
                                code, ms, "HTTP " + code);
                    }
                    return new CheckResult(CheckResult.Outcome.DOWN, 0, ms, describe(ex));
                });
    }

    /** Turns an exception into the short readable reason stored in last_detail. */
    static String describe(Throwable ex) {
        Throwable t = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String msg;
        if (t instanceof HttpConnectTimeoutException) {
            msg = "Timeout - could not connect in time";
        } else if (t instanceof HttpTimeoutException) {
            msg = "Timeout - no response in time";
        } else if (root instanceof UnknownHostException || root instanceof UnresolvedAddressException) {
            msg = "Unknown host (DNS failed)";
        } else if (t instanceof ConnectException || root instanceof ConnectException) {
            msg = "Couldn't connect to server";
        } else if (root instanceof SSLException) {
            msg = "SSL error: " + (root.getMessage() != null ? root.getMessage()
                    : root.getClass().getSimpleName());
        } else {
            msg = root.getClass().getSimpleName()
                    + (root.getMessage() != null ? ": " + root.getMessage() : "");
        }
        return Text.clean(msg, 250);
    }

    private static SSLContext trustAllContext() {
        try {
            TrustManager[] trustAll = { new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            } };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Could not create a trust-all SSL context", e);
        }
    }
}
