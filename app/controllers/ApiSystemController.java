package controllers;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import models.TaskRun;
import play.mvc.Controller;
import play.mvc.With;
import services.RestartService;
import services.SubagentRegistry;
import utils.ApiResponses;

import static utils.GsonHolder.GSON;

/**
 * Instance-level maintenance operations — currently just restart
 * (Settings → Restart).
 *
 * <p>Restart is deliberately not gated on in-flight work. The moment an
 * operator most wants to reboot is usually the moment something is wedged, so
 * a 409 while a stuck task "runs" would lock them out of the one control that
 * clears it. Instead {@link #restartPreflight()} reports what is in flight and
 * the UI makes the operator confirm against it.
 */
@With(AuthCheck.class)
public class ApiSystemController extends Controller {

    /**
     * @param available          whether a restart can be performed at all —
     *                           false when this install has no {@code jclaw.sh}
     *                           to hand off to
     * @param unavailableReason  why not, or null when {@code available}
     * @param mode               {@code "DEV"} or {@code "PROD"}
     * @param backendOnly        true when the Nuxt dev server will be spared
     *                           (dev mode); the browser keeps its connection
     * @param rebuildExpected    true when the restart may recompile sources and
     *                           rebuild the SPA — a source tree rather than a
     *                           packaged install. Coarse by design; see
     *                           {@link RestartService.Plan}
     * @param runningTasks       task runs in RUNNING state, which a restart
     *                           interrupts
     * @param activeSubagentRuns subagent runs live in THIS JVM, which a restart
     *                           interrupts
     */
    public record RestartPreflight(boolean available, String unavailableReason, String mode,
                                   boolean backendOnly, boolean rebuildExpected,
                                   long runningTasks, int activeSubagentRuns) {}

    /**
     * GET /api/system/restart — what a restart would do and what it would
     * interrupt. Drives the confirmation dialog; performs nothing.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RestartPreflight.class)))
    public static void restartPreflight() {
        var plan = RestartService.plan();
        var unavailable = RestartService.unavailableReason();

        renderJSON(GSON.toJson(new RestartPreflight(
                unavailable == null, unavailable, plan.mode(),
                plan.backendOnly(), plan.rebuildExpected(),
                TaskRun.count("status = ?1", TaskRun.Status.RUNNING),
                SubagentRegistry.activeRunIds().size())));
    }

    /**
     * POST /api/system/restart — hand off to the restart helper and ack.
     *
     * <p>Returns 202, not 200: the reboot has been accepted, not completed.
     * This JVM keeps serving for a couple more seconds so this very response
     * can reach the browser, then the helper stops it.
     */
    public static void restart() {
        // The success render stays OUT of the try. Play signals results by
        // throwing, and RenderJson is a RuntimeException — inside the try below
        // the catch-all would swallow its own 202 and answer 500 instead.
        RestartService.Plan plan;
        try {
            plan = RestartService.requestRestart();
        } catch (IllegalStateException e) {
            // Install can't be restarted (no jclaw.sh) — the app is still up,
            // so this is a plain refusal rather than a half-completed reboot.
            ApiResponses.error(409, ApiResponses.CONFLICT, e.getMessage());
            return;
        } catch (Exception e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR,
                    "Failed to launch the restart helper: " + e.getMessage());
            return;
        }

        response.status = 202;
        ApiResponses.ok("mode", plan.mode(),
                "backendOnly", plan.backendOnly(),
                "rebuildExpected", plan.rebuildExpected());
    }
}
