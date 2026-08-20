package services.scrape;

/**
 * Rungs of the escalation ladder, in cost order (JCLAW-1086).
 *
 * <p>Only {@link #PLAIN} is implemented; the rest land with JCLAW-1087/1088/1089.
 * The enum exists now because {@link BlockClassifier#nextRung} is the decision those
 * stories will consume, and the decision is testable before any of them are built.
 */
public enum ScrapeRung {
    /** OkHttp + SsrfGuard + Readability. What ships today. */
    PLAIN,
    /** TLS/HTTP2 fingerprint impersonation (JCLAW-1087). */
    IMPERSONATE,
    /** Real browser — renders JavaScript and defeats browser fingerprinting (JCLAW-1088). */
    BROWSER,
    /** Paid provider with a residential pool (JCLAW-1089/1090). */
    PROVIDER,
    /** Nothing further will help. */
    NONE
}
