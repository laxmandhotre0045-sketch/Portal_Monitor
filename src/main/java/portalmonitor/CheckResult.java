package portalmonitor;

/**
 * The outcome of one check.
 *
 * INDETERMINATE exists for Docker's "health: starting" - a container that is still
 * warming up is neither a pass nor a failure, so the previous state is kept and the
 * failure streak is left untouched.
 *
 * httpCode is 0 and responseMs is 0 for CONTAINER checks.
 */
public record CheckResult(Outcome outcome, int httpCode, long responseMs, String detail) {

    public enum Outcome { UP, DOWN, INDETERMINATE }

    public static CheckResult up(String detail) {
        return new CheckResult(Outcome.UP, 0, 0, detail);
    }

    public static CheckResult down(String detail) {
        return new CheckResult(Outcome.DOWN, 0, 0, detail);
    }

    public static CheckResult starting(String detail) {
        return new CheckResult(Outcome.INDETERMINATE, 0, 0, detail);
    }

    public boolean isUp() {
        return outcome == Outcome.UP;
    }

    public boolean isDown() {
        return outcome == Outcome.DOWN;
    }

    public boolean isIndeterminate() {
        return outcome == Outcome.INDETERMINATE;
    }
}
