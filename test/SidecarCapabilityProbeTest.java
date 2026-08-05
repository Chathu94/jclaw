import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.SidecarCapabilityProbe;
import services.SidecarCapabilityProbe.State;

/**
 * The shared host-capability probe behind the imagegen and videogen Settings gates.
 *
 * <p>Every case here runs against a sidecar directory that does not exist, which is the
 * situation the probe is written for: a fresh install has no sidecar, and the panel needs
 * a definite verdict rather than an exception or a spinner that never resolves. The
 * contract is that a missing sidecar reaches a terminal state carrying a reason.
 */
class SidecarCapabilityProbeTest extends UnitTest {

    private static SidecarCapabilityProbe probeForMissingSidecar() {
        return new SidecarCapabilityProbe("sidecar/does-not-exist", "probe-test", "test");
    }

    /** Wait for the async probe to leave PROBING, or fail loudly rather than hang. */
    private static State awaitTerminal(SidecarCapabilityProbe p) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            var s = p.snapshot().state();
            if (s != State.PROBING && s != State.NEEDS_PROBE) return s;
            Thread.sleep(50);
        }
        throw new AssertionError("probe never left PROBING — a panel polling this would spin forever");
    }

    @Test
    void aFreshProbeReportsThatItHasNotRunYet() {
        // NEEDS_PROBE is what makes the panel offer the button instead of a stale verdict.
        var p = probeForMissingSidecar();
        var snap = p.snapshot();

        assertEquals(State.NEEDS_PROBE, snap.state());
        assertNull(snap.error(), "nothing has failed yet, so there is nothing to report");
        assertNull(snap.capability(), "no capability is known before a probe runs");
    }

    @Test
    void snapshotIsSafeToPollRepeatedlyBeforeAnyProbe() {
        // The Settings panel polls this on a timer; it must not mutate state or throw.
        var p = probeForMissingSidecar();
        assertEquals(State.NEEDS_PROBE, p.snapshot().state());
        assertEquals(State.NEEDS_PROBE, p.snapshot().state());
        assertEquals(State.NEEDS_PROBE, p.snapshot().state());
    }

    @Test
    void probingAMissingSidecarReachesATerminalStateRatherThanHanging() throws InterruptedException {
        var p = probeForMissingSidecar();
        p.probe();
        var terminal = awaitTerminal(p);

        assertNotSame(State.PROBING, terminal);
        assertTrue(terminal == State.UNAVAILABLE || terminal == State.ERROR,
                "a sidecar that is not on disk cannot be READY; got " + terminal);
    }

    @Test
    void aFailedProbeSaysWhyRatherThanJustFailing() throws InterruptedException {
        // An operator seeing UNAVAILABLE with no reason has nothing to act on.
        var p = probeForMissingSidecar();
        p.probe();
        awaitTerminal(p);
        var snap = p.snapshot();

        // Not a disjunction with uvReason: that is non-null on every path (it reads
        // "available" on success), so the || made the assertion unconditionally true.
        assertNotNull(snap.error(), "a terminal failure must say what failed");
        assertNull(snap.capability(), "a failed probe must not claim a capability");
    }

    @Test
    void twoProbesOnTheSameInstanceStillLandOnATerminalState() throws InterruptedException {
        // Double-clicking the button in Settings must not wedge the probe.
        var p = probeForMissingSidecar();
        p.probe();
        p.probe();
        var terminal = awaitTerminal(p);

        assertTrue(terminal == State.UNAVAILABLE || terminal == State.ERROR, terminal.name());
    }

    @Test
    void separateProbeInstancesDoNotShareState() {
        // imagegen and videogen each own one; a verdict for one must not answer for the other.
        var a = new SidecarCapabilityProbe("sidecar/does-not-exist-a", "probe-a", "a");
        var b = new SidecarCapabilityProbe("sidecar/does-not-exist-b", "probe-b", "b");

        a.probe();
        assertEquals(State.NEEDS_PROBE, b.snapshot().state(),
                "probing one sidecar must not mark the other as probed");
    }
}
