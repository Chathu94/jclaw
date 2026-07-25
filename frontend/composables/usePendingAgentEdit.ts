/**
 * One-shot handoff for "open this agent's edit form", set by the command
 * palette and consumed by {@code /agents}.
 *
 * Shared state rather than a {@code ?edit=<id>} query param because the edit
 * form is deliberately not a URL location — see {@link useBreadcrumbExtra},
 * which exists precisely because agents.vue edits in place on the same
 * {@code /agents} route. A param would also have to be stripped after use, and
 * re-picking the agent already named in the URL would be a same-URL push the
 * page never sees.
 *
 * The consumer resets it to {@code null}, so picking the same agent twice works
 * the same as picking two different ones.
 */
export function usePendingAgentEdit() {
  return useState<number | null>('agents-pending-edit', () => null)
}
