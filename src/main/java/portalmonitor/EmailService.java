package portalmonitor;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Sends HTML + plain-text email over SMTP (Hostinger: smtp.hostinger.com, 465/SSL or 587/STARTTLS). */
public final class EmailService {

    /** Outcome of one send attempt; error is null on success. */
    public record SendResult(boolean ok, String error) { }

    private final Config cfg;
    private final Session session;

    public EmailService(Config cfg) {
        this.cfg = cfg;

        Properties p = new Properties();
        p.put("mail.smtp.host", cfg.smtpHost);
        p.put("mail.smtp.port", String.valueOf(cfg.smtpPort));
        // Bounded timeouts: a hung SMTP server must not stall the check cycle.
        p.put("mail.smtp.connectiontimeout", "20000");
        p.put("mail.smtp.timeout", "20000");
        p.put("mail.smtp.writetimeout", "20000");

        boolean auth = !cfg.smtpUser.isEmpty();
        p.put("mail.smtp.auth", String.valueOf(auth));

        switch (cfg.smtpSecurity) {
            case "ssl" -> {                                  // implicit TLS, port 465
                p.put("mail.smtp.ssl.enable", "true");
                p.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
            }
            case "tls" -> {                                  // STARTTLS, port 587
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
                p.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
            }
            default -> { /* "none": plain SMTP, local testing only */ }
        }

        Authenticator a = auth ? new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(cfg.smtpUser, cfg.smtpPassword);
            }
        } : null;

        this.session = Session.getInstance(p, a);
    }

    /** "a@x.com, b@y.com; c@z.com" -> valid, de-duplicated addresses. Invalid entries are dropped. */
    public static List<String> parseEmails(String list) {
        if (list == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String s : list.split("[\\s,;]+")) {
            if (s.isBlank()) continue;
            try {
                InternetAddress ia = new InternetAddress(s, true);
                ia.validate();
                out.add(ia.getAddress());
            } catch (AddressException ignored) {
                // skip: one typo in notify_email must not block the other recipients
            }
        }
        return List.copyOf(out);
    }

    public SendResult send(String recipients, String subject, String html, String text) {
        List<String> to = parseEmails(recipients);
        if (to.isEmpty()) {
            return new SendResult(false, "No valid email address in: " + recipients);
        }
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(cfg.smtpFrom, cfg.smtpFromName, "UTF-8"));
            for (String addr : to) {
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(addr));
            }
            msg.setSubject(subject, "UTF-8");
            msg.setSentDate(new Date());

            // multipart/alternative: plain text first, HTML last (clients show the last part they can render)
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(text, "UTF-8");
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(html, "text/html; charset=UTF-8");
            MimeMultipart body = new MimeMultipart("alternative");
            body.addBodyPart(textPart);
            body.addBodyPart(htmlPart);
            msg.setContent(body);

            Transport.send(msg);
            return new SendResult(true, null);
        } catch (Exception e) {
            // Never throw: a failed email is logged to alert_logs and the service keeps running.
            String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new SendResult(false, m.replaceAll("\\s+", " ").trim());
        }
    }
}
