package portalmonitor;

/** Small string helpers used when writing to fixed-width columns and log lines. */
public final class Text {

    private Text() { }

    /** Keeps a value inside its VARCHAR limit so a long message can never fail an INSERT. */
    public static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Collapses newlines and runs of whitespace so a message stays on one log line. */
    public static String oneLine(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").trim();
    }

    /** One-line and length-capped, which is what every detail / error column wants. */
    public static String clean(String s, int max) {
        return truncate(oneLine(s), max);
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
