package portalmonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailServiceTest {

    @Test
    @DisplayName("a single address parses")
    void single() {
        assertEquals(List.of("ops@example.com"), EmailService.parseEmails("ops@example.com"));
    }

    @Test
    @DisplayName("comma, semicolon, space, tab and newline all separate addresses")
    void separators() {
        List<String> expected = List.of("a@example.com", "b@example.com", "c@example.com");
        assertEquals(expected, EmailService.parseEmails("a@example.com,b@example.com,c@example.com"));
        assertEquals(expected, EmailService.parseEmails("a@example.com, b@example.com; c@example.com"));
        assertEquals(expected, EmailService.parseEmails("  a@example.com   b@example.com\tc@example.com "));
        assertEquals(expected, EmailService.parseEmails("a@example.com,\nb@example.com\nc@example.com"));
    }

    @Test
    @DisplayName("duplicates collapse and order is kept")
    void deduplicates() {
        assertEquals(List.of("a@example.com", "b@example.com"),
                EmailService.parseEmails("a@example.com, b@example.com, a@example.com"));
    }

    @Test
    @DisplayName("one bad address does not lose the good ones")
    void skipsInvalid() {
        assertEquals(List.of("good@example.com", "also@example.com"),
                EmailService.parseEmails("good@example.com, not-an-email, also@example.com"));
    }

    @Test
    @DisplayName("empty and null give an empty list rather than throwing")
    void emptyInputs() {
        assertTrue(EmailService.parseEmails(null).isEmpty());
        assertTrue(EmailService.parseEmails("").isEmpty());
        assertTrue(EmailService.parseEmails("   ").isEmpty());
        assertTrue(EmailService.parseEmails(",,; ").isEmpty());
        assertTrue(EmailService.parseEmails("nonsense").isEmpty());
    }

    // ---------------------------------------------------------------- error wording

    @Test
    @DisplayName("exceptions map to the readable reasons stored in last_detail")
    void describesErrors() {
        assertEquals("Unknown host (DNS failed)",
                HttpChecker.describe(new java.net.UnknownHostException("no-such-host.invalid")));
        assertEquals("Couldn't connect to server",
                HttpChecker.describe(new java.net.ConnectException("Connection refused")));
        assertEquals("Timeout - no response in time",
                HttpChecker.describe(new java.net.http.HttpTimeoutException("request timed out")));
        assertEquals("Timeout - could not connect in time",
                HttpChecker.describe(new java.net.http.HttpConnectTimeoutException("connect timed out")));
        assertTrue(HttpChecker.describe(new javax.net.ssl.SSLHandshakeException("cert expired"))
                .startsWith("SSL error: "));
    }

    @Test
    @DisplayName("a wrapped cause is unwrapped to the real reason")
    void unwrapsCompletionException() {
        Throwable wrapped = new java.util.concurrent.CompletionException(
                new java.io.IOException("outer", new java.net.UnknownHostException("inner")));

        assertEquals("Unknown host (DNS failed)", HttpChecker.describe(wrapped));
    }

    @Test
    @DisplayName("a multi-line exception message collapses to one line")
    void collapsesNewlines() {
        String reason = HttpChecker.describe(new IllegalStateException("line one\nline two\tand\r\nthree"));

        assertTrue(reason.contains("line one line two and three"), "got: " + reason);
        assertEquals(-1, reason.indexOf('\n'), "no newline may survive");
        assertEquals(-1, reason.indexOf('\t'), "no tab may survive");
    }

    @Test
    @DisplayName("a reason never exceeds the last_detail column width")
    void reasonFitsColumn() {
        String reason = HttpChecker.describe(new IllegalStateException("x".repeat(5000)));

        assertTrue(reason.length() <= 250, "reason was " + reason.length() + " chars");
    }

    // ---------------------------------------------------------------- text helpers

    @Test
    @DisplayName("Text.clean both flattens and truncates")
    void textClean() {
        assertEquals("a b c", Text.clean("  a\n b\tc  ", 100));
        assertEquals("abcde", Text.clean("abcdefghij", 5));
        assertEquals(null, Text.clean(null, 10));
        assertTrue(Text.isBlank(null));
        assertTrue(Text.isBlank("   "));
        assertTrue(!Text.isBlank("x"));
    }
}
