/**
 * Flag recording that the "Leave a star!" nudge has had its moment.
 *
 * The nudge shows at most once per *login session*, not once per browser:
 * it writes this key when it appears, and `useAuth.login()` clears it so
 * signing back in surfaces the pointer again. Defined here rather than
 * inline in both places so the writer and the clearer can't drift apart on
 * a string literal.
 */
export const STAR_NUDGE_SEEN_KEY = 'jclaw-star-nudge-seen'

/** Re-arm the nudge for a new session. No-op when storage is unavailable. */
export function resetStarNudge() {
  try {
    localStorage.removeItem(STAR_NUDGE_SEEN_KEY)
  }
  catch {
    // Private-mode localStorage throws on access — nothing to reset.
  }
}
