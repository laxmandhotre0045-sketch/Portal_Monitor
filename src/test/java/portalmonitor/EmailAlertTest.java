package portalmonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Email sending must fail softly - a broken SMTP server can never stop monitoring. */
class EmailAlertTest {

    @Test
    @DisplayName("an unreachable SMTP server gives a failed SendResult, retried, never an exception")
    void unreachableSmtp() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Properties p = new Properties();
        p.setProperty("smtp.host", "127.0.0.1");
        p.setProperty("smtp.port", String.valueOf(port));
        p.setProperty("smtp.security", "none");
        p.setProperty("smtp.from", "monitor@example.com");
        EmailService mail = new EmailService(new Config(p), new long[] {10, 10});

        long start = System.nanoTime();
        EmailService.SendResult r = mail.send("ops@example.com", "s", "<p>h</p>", "t");
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertFalse(r.ok());
        assertTrue(r.error().toLowerCase().contains("connect"), r.error());
        assertTrue(ms < 15_000, "three quick attempts, not a hang: " + ms + "ms");
    }

    @Test
    @DisplayName("no valid recipient fails immediately without touching SMTP")
    void noRecipient() {
        Properties p = new Properties();
        p.setProperty("smtp.host", "127.0.0.1");
        p.setProperty("smtp.from", "monitor@example.com");
        EmailService.SendResult r = new EmailService(new Config(p)).send("not-an-address", "s", "h", "t");
        assertFalse(r.ok());
        assertTrue(r.error().contains("No valid email address"));
    }
}
