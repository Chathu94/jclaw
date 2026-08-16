package controllers;

import play.mvc.Scope;
import services.InternalApiTokenService;

/**
 * Who is making this request — the operator at a browser, or an agent driving the API through
 * the {@code jclaw_api} tool (JCLAW-1023).
 *
 * <p>Both arrive at a controller looking identical: {@link AuthCheck}'s bearer branch stashes
 * {@code authenticated}/{@code username} into the session exactly as a cookie login does, so
 * session state alone cannot separate them.
 *
 * <p>The test is therefore the authentication <em>mechanism</em>, stamped by {@link AuthCheck}
 * when it accepts a bearer token, with the owner name as a second signal. Gating on the
 * mechanism rather than on a username string matters twice: an operator who set their own
 * username to {@code system} would otherwise lock themselves out, and any future token row
 * stamped with some other owner would otherwise be trusted by default.
 */
public final class RequestPrincipal {

    /** Session key {@link AuthCheck} stamps when a request authenticated by bearer token. */
    static final String PRINCIPAL_KEY = "principal";

    /** Value of {@link #PRINCIPAL_KEY} for a bearer-authenticated (agent) request. */
    static final String AGENT = "agent";

    private RequestPrincipal() {}

    /**
     * True when this request came in on the internal bearer rather than an operator session —
     * i.e. an agent is calling the API on its own behalf.
     *
     * <p>Either signal is sufficient. The mechanism marker is authoritative; the owner-name
     * check keeps the answer right for a session minted before this marker existed.
     */
    public static boolean isAgentOriginated() {
        var session = Scope.Session.current();
        if (session == null) return false;
        return AGENT.equals(session.get(PRINCIPAL_KEY))
                || InternalApiTokenService.SYSTEM_OWNER.equals(session.get("username"));
    }
}
