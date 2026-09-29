package portalmonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives MonitorService.decide - the whole UP/DOWN state machine - with no database and
 * no SMTP. This is what decides whether an email is sent, so it is pinned down closely.
 */
class MonitorStateTest {

    private static final int THRESHOLD = 3;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 15, 10, 0, 0);

    private static LocalDateTime at(int minutes) {
        return T0.plusMinutes(minutes);
    }

    private static Portal container(String status, int failCount, LocalDateTime failSince,
                                    LocalDateTime lastChange) {
        return new Portal(1, "tms-backend", "TMS", Portal.CheckType.CONTAINER, "tms-backend", null,
                "ops@example.com", status, failCount, failSince, lastChange);
    }

    private static final CheckResult RUNNING = CheckResult.up("Up 3 hours (healthy)");
    private static final CheckResult EXITED = CheckResult.down("Exited (1) 5 minutes ago");
    private static final CheckResult STARTING = CheckResult.starting("health: starting");

    private static MonitorService.Decision decide(Portal p, CheckResult r, LocalDateTime now) {
        return MonitorService.decide(p, r, now, THRESHOLD);
    }

    /** Applies a decision to a portal, standing in for the UPDATE plus the next SELECT. */
    private static Portal next(Portal p, MonitorService.Decision d) {
        return new Portal(p.id(), p.name(), p.appGroup(), p.checkType(), p.containerName(),
                p.healthUrl(), p.notifyEmail(), d.status(), d.failCount(), d.failSince(),
                d.statusChange());
    }

    // ---------------------------------------------------------------- first check

    @Test
    @DisplayName("first check healthy: UNKNOWN -> UP, no email")
    void firstCheckUp() {
        MonitorService.Decision d = decide(container(Portal.UNKNOWN, 0, null, null), RUNNING, T0);

        assertEquals(Portal.UP, d.status());
        assertEquals(0, d.failCount());
        assertNull(d.failSince());
        assertEquals(T0, d.statusChange());
        assertNull(d.alert(), "UNKNOWN -> UP must not send an email");
    }

    @Test
    @DisplayName("first check failing: stays UNKNOWN, counts 1, no email yet")
    void firstCheckDown() {
        MonitorService.Decision d = decide(container(Portal.UNKNOWN, 0, null, null), EXITED, T0);

        assertEquals(Portal.UNKNOWN, d.status(), "one failure is not enough to declare DOWN");
        assertEquals(1, d.failCount());
        assertEquals(T0, d.failSince());
        assertNull(d.alert());
    }

    // ---------------------------------------------------------------- threshold

    @Test
    @DisplayName("3 consecutive failures send exactly one DOWN email")
    void threeFailuresSendOneDownEmail() {
        Portal p = container(Portal.UP, 0, null, at(0));
        int emails = 0;

        MonitorService.Decision d = decide(p, EXITED, at(1));
        assertEquals(Portal.UP, d.status(), "still UP after 1 failure");
        assertEquals(1, d.failCount());
        assertEquals(at(1), d.failSince());
        assertNull(d.alert());
        p = next(p, d);

        d = decide(p, EXITED, at(2));
        assertEquals(Portal.UP, d.status(), "still UP after 2 failures");
        assertEquals(2, d.failCount());
        assertEquals(at(1), d.failSince(), "failSince stays at the first failure");
        assertNull(d.alert());
        p = next(p, d);

        d = decide(p, EXITED, at(3));
        assertEquals(Portal.DOWN, d.status());
        assertEquals(3, d.failCount());
        assertEquals(at(1), d.failSince());
        assertEquals(at(3), d.statusChange(), "the status changed on this check");
        assertEquals("DOWN", d.alert());
        emails++;

        assertEquals(1, emails, "exactly one DOWN email");
    }

    @Test
    @DisplayName("further failures after the DOWN email send nothing")
    void noRepeatEmailsWhileDown() {
        Portal p = container(Portal.DOWN, 3, at(1), at(3));

        for (int i = 4; i <= 30; i++) {
            MonitorService.Decision d = decide(p, EXITED, at(i));
            assertNull(d.alert(), "no repeat email at failure " + d.failCount());
            assertEquals(Portal.DOWN, d.status());
            assertEquals(at(1), d.failSince(), "failSince is frozen at the first failure");
            assertEquals(at(3), d.statusChange(), "statusChange must not move while it stays down");
            p = next(p, d);
        }
        assertEquals(30, p.failCount());
    }

    // ---------------------------------------------------------------- recovery

    @Test
    @DisplayName("recovery sends one RECOVERED email with the right downtime")
    void recoverySendsOneEmailWithDowntime() {
        LocalDateTime failSince = at(1);
        LocalDateTime recoveredAt = at(73);          // 1h 12m later
        Portal p = container(Portal.DOWN, 12, failSince, at(3));

        MonitorService.Decision d = decide(p, RUNNING, recoveredAt);

        assertEquals(Portal.UP, d.status());
        assertEquals("RECOVERED", d.alert());
        assertEquals(0, d.failCount(), "the streak is cleared");
        assertNull(d.failSince(), "failSince is cleared so the next outage starts fresh");
        assertEquals(recoveredAt, d.statusChange());

        EmailTemplates.Email email = EmailTemplates.stateChange("RECOVERED",
                java.util.List.of(new MonitorService.PendingAlert(p, RUNNING, failSince, 0, "RECOVERED")),
                recoveredAt);
        assertEquals("\uD83D\uDFE2 RECOVERED: tms-backend", email.subject());
        assertTrue(email.text().contains("1h 12m"), "downtime should read 1h 12m:\n" + email.text());

        MonitorService.Decision after = decide(next(p, d), RUNNING, at(74));
        assertNull(after.alert(), "no email once it is already UP");
        assertEquals(recoveredAt, after.statusChange());
    }

    @Test
    @DisplayName("a blip below the threshold is silent")
    void flapBelowThresholdIsSilent() {
        Portal p = container(Portal.UP, 0, null, at(0));

        MonitorService.Decision d1 = decide(p, EXITED, at(1));
        assertNull(d1.alert());
        MonitorService.Decision d2 = decide(next(p, d1), EXITED, at(2));
        assertNull(d2.alert());

        MonitorService.Decision d3 = decide(next(p, d2), RUNNING, at(3));
        assertNull(d3.alert(), "recovering before the threshold must not email");
        assertEquals(Portal.UP, d3.status());
        assertEquals(0, d3.failCount());
        assertNull(d3.failSince());
        assertEquals(at(0), d3.statusChange(), "it never left UP, so statusChange must not move");
    }

    @Test
    @DisplayName("a second outage after recovery alerts again")
    void secondOutageAlertsAgain() {
        Portal p = container(Portal.UP, 0, null, at(100));

        MonitorService.Decision d = null;
        for (int i = 101; i <= 103; i++) {
            d = decide(p, EXITED, at(i));
            p = next(p, d);
        }
        assertEquals("DOWN", d.alert());
        assertEquals(at(101), d.failSince());
        assertEquals(3, d.failCount());
    }

    // ---------------------------------------------------------------- health: starting

    @Test
    @DisplayName("health: starting holds the state and does not touch the streak")
    void startingKeepsPreviousState() {
        // from UP
        MonitorService.Decision fromUp = decide(container(Portal.UP, 0, null, at(0)), STARTING, at(5));
        assertEquals(Portal.UP, fromUp.status());
        assertEquals(0, fromUp.failCount());
        assertNull(fromUp.alert(), "a starting container must not alert");
        assertEquals(at(0), fromUp.statusChange(), "statusChange must not move");

        // from DOWN - it must stay DOWN and NOT be treated as a recovery
        MonitorService.Decision fromDown =
                decide(container(Portal.DOWN, 5, at(1), at(3)), STARTING, at(9));
        assertEquals(Portal.DOWN, fromDown.status());
        assertEquals(5, fromDown.failCount(), "the streak is left exactly as it was");
        assertEquals(at(1), fromDown.failSince());
        assertNull(fromDown.alert(), "starting is not a recovery");
        assertEquals(at(3), fromDown.statusChange());

        // from UNKNOWN
        MonitorService.Decision fromUnknown =
                decide(container(Portal.UNKNOWN, 0, null, null), STARTING, at(9));
        assertEquals(Portal.UNKNOWN, fromUnknown.status());
        assertNull(fromUnknown.alert());
    }

    @Test
    @DisplayName("a container stuck starting never crosses the DOWN threshold")
    void startingNeverAccumulatesFailures() {
        Portal p = container(Portal.UP, 0, null, at(0));
        for (int i = 1; i <= 10; i++) {
            MonitorService.Decision d = decide(p, STARTING, at(i));
            assertNull(d.alert(), "cycle " + i + " must stay silent");
            assertEquals(0, d.failCount());
            p = next(p, d);
        }
        assertEquals(Portal.UP, p.status());
    }

    @Test
    @DisplayName("starting between two failures does not reset the streak")
    void startingBetweenFailuresKeepsStreak() {
        Portal p = container(Portal.UP, 0, null, at(0));

        p = next(p, decide(p, EXITED, at(1)));       // fail 1
        assertEquals(1, p.failCount());

        p = next(p, decide(p, STARTING, at(2)));     // held
        assertEquals(1, p.failCount(), "still 1 - starting neither adds nor clears");

        p = next(p, decide(p, EXITED, at(3)));       // fail 2
        assertEquals(2, p.failCount());

        MonitorService.Decision d = decide(p, EXITED, at(4));   // fail 3 -> DOWN
        assertEquals("DOWN", d.alert());
        assertEquals(3, d.failCount());
        assertEquals(at(1), d.failSince(), "the streak still dates from the first failure");
    }

    // ---------------------------------------------------------------- other thresholds

    @Test
    @DisplayName("failThreshold=1 alerts on the first failure")
    void thresholdOne() {
        MonitorService.Decision d =
                MonitorService.decide(container(Portal.UP, 0, null, at(0)), EXITED, at(1), 1);

        assertEquals(Portal.DOWN, d.status());
        assertEquals("DOWN", d.alert());
        assertEquals(1, d.failCount());
    }

    @Test
    @DisplayName("a row edited to UP by hand still re-alerts on the next outage")
    void manualStatusEditStillAlerts() {
        MonitorService.Decision d = decide(container(Portal.UP, 5, at(1), at(3)), EXITED, at(9));

        assertEquals(Portal.DOWN, d.status());
        assertEquals("DOWN", d.alert());
        assertEquals(6, d.failCount());
    }
}
