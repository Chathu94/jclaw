package services;

import controllers.RequestPrincipal;
import models.Agent;
import models.Team;
import models.TeamMembership;
import models.Tenant;
import models.UserAccount;
import models.UserRole;
import play.Play;
import play.mvc.Scope;

import java.time.Instant;
import java.util.List;

/**
 * Tenant/team/user access boundary for the admin API.
 *
 * <p>Legacy installs start with only the single admin password in Config. The
 * first authenticated request lazily creates the default tenant, default team,
 * and an ALL_ADMIN user matching {@code jclaw.admin.username}, so old data stays
 * usable while new rows gain ownership metadata.
 */
public class AccessControlService {

    public record Principal(UserAccount user, UserRole role, Tenant tenant, Team team,
                            boolean agentOriginated) {
        public boolean allAdmin() {
            return role == UserRole.ALL_ADMIN;
        }
    }

    private AccessControlService() {}

    public static Principal currentPrincipal() {
        var username = sessionUsername();
        if (username == null || username.isBlank()) {
            username = RequestPrincipal.isAgentOriginated() ? "system" : adminUsername();
        }
        var user = ensureUser(username);
        return new Principal(user, user.role, user.tenant, user.primaryTeam,
                RequestPrincipal.isAgentOriginated());
    }

    public static UserAccount currentUser() {
        return currentPrincipal().user();
    }

    public static Tenant defaultTenant() {
        var tenant = Tenant.findBySlug(Tenant.DEFAULT_SLUG);
        if (tenant != null) return tenant;
        tenant = new Tenant();
        tenant.slug = Tenant.DEFAULT_SLUG;
        tenant.name = "Default";
        tenant.enabled = true;
        tenant.save();
        return tenant;
    }

    public static Team defaultTeam() {
        var tenant = defaultTenant();
        var team = Team.findBySlug(tenant, Team.DEFAULT_SLUG);
        if (team != null) return team;
        team = new Team();
        team.tenant = tenant;
        team.slug = Team.DEFAULT_SLUG;
        team.name = "Default";
        team.enabled = true;
        team.save();
        return team;
    }

    public static UserAccount ensureUser(String username) {
        var normalized = normalizeUsername(username);
        var user = UserAccount.findByUsername(normalized);
        if (user != null) {
            if (isBootstrapAdmin(normalized) && user.role == UserRole.ALL_ADMIN && user.approvedAt == null) {
                user.approvedAt = Instant.now();
                user.save();
            }
            return user;
        }

        var tenant = defaultTenant();
        var team = defaultTeam();
        user = new UserAccount();
        user.username = normalized;
        user.displayName = normalized;
        user.tenant = tenant;
        user.primaryTeam = team;
        user.role = isBootstrapAdmin(normalized) ? UserRole.ALL_ADMIN : UserRole.USER;
        user.enabled = true;
        if (user.role == UserRole.ALL_ADMIN) user.approvedAt = Instant.now();
        user.save();
        ensureMembership(user, team, user.role);
        return user;
    }

    public static void ensureMembership(UserAccount user, Team team, UserRole role) {
        if (user == null || team == null) return;
        var membership = TeamMembership.findByUserAndTeam(user, team);
        if (membership == null) {
            membership = new TeamMembership();
            membership.user = user;
            membership.team = team;
        }
        membership.role = role == UserRole.ALL_ADMIN || role == UserRole.TENANT_ADMIN
                ? UserRole.TEAM_ADMIN : role;
        membership.save();
    }

    /**
     * Every login-capable non-bootstrap user gets a personal top-level agent. The
     * legacy {@code main} row stays the bootstrap admin's agent; everyone else
     * receives a stable {@code user-<id>-main} row, which gives them a separate
     * workspace directory and separate {@code agent.<name>.*} config keys.
     */
    public static Agent ensurePersonalAgent(UserAccount user) {
        if (user == null || user.id == null) return null;
        if (isBootstrapAdmin(user.username)) return Agent.findByName(Agent.MAIN_AGENT_NAME);

        var name = "user-" + user.id + "-main";
        var existing = Agent.findByName(name);
        if (existing != null) {
            boolean touched = false;
            if (existing.tenant == null && user.tenant != null) {
                existing.tenant = user.tenant;
                touched = true;
            }
            if (existing.team == null && user.primaryTeam != null) {
                existing.team = user.primaryTeam;
                touched = true;
            }
            if (existing.ownerUser == null) {
                existing.ownerUser = user;
                touched = true;
            }
            if (touched) existing.save();
            AgentService.createWorkspace(existing.name);
            return existing;
        }

        var template = Agent.findByName(Agent.MAIN_AGENT_NAME);
        var provider = template != null ? template.modelProvider : "ollama-cloud";
        var model = template != null ? template.modelId : "kimi-k2.5";
        var thinking = template != null ? template.thinkingMode : null;
        var display = user.displayName != null && !user.displayName.isBlank()
                ? user.displayName : user.username;
        return AgentService.createForOwner(name, provider, model, thinking,
                "Personal default agent for " + display, user);
    }

    public static void stampNewAgent(Agent agent) {
        if (agent == null || agent.parentAgent != null) return;
        var principal = currentPrincipal();
        agent.tenant = principal.tenant();
        agent.team = principal.team();
        agent.ownerUser = principal.user();
    }

    public static void inheritScope(Agent child, Agent parent) {
        if (child == null || parent == null) return;
        child.tenant = parent.tenant;
        child.team = parent.team;
        child.ownerUser = parent.ownerUser;
    }

    public static boolean canReadAgent(Agent agent) {
        if (agent == null) return false;
        if (isLegacyUnscoped(agent)) assignLegacyAgentScope(agent);
        var principal = currentPrincipal();
        return canSee(principal, agent.tenant, agent.team, agent.ownerUser);
    }

    public static List<Agent> filterReadableAgents(List<Agent> agents) {
        var principal = currentPrincipal();
        return filterReadableAgents(principal, agents);
    }

    public static List<Agent> filterReadableAgents(Principal principal, List<Agent> agents) {
        return agents.stream().filter(a -> canReadAgent(principal, a)).toList();
    }

    public static boolean canSee(Principal principal, Tenant tenant, Team team, UserAccount owner) {
        if (principal == null || principal.user() == null
                || !principal.user().enabled || !principal.user().isApprovedForAccess()) return false;
        if (principal.role() == UserRole.ALL_ADMIN) return true;
        if (tenant == null || principal.tenant() == null || !tenant.id.equals(principal.tenant().id)) return false;
        if (principal.role() == UserRole.TENANT_ADMIN) return true;
        if (team != null && isTeamAdmin(principal.user(), team)) return true;
        return owner != null && owner.id.equals(principal.user().id);
    }

    public static boolean canReadAgent(Principal principal, Agent agent) {
        if (agent == null) return false;
        if (isLegacyUnscoped(agent)) assignLegacyAgentScope(agent);
        return canSee(principal, agent.tenant, agent.team, agent.ownerUser);
    }

    public static void assignLegacyAgentScope(Agent agent) {
        if (agent == null || !isLegacyUnscoped(agent)) return;
        agent.tenant = defaultTenant();
        agent.team = defaultTeam();
        agent.ownerUser = ensureUser(adminUsername());
        agent.save();
    }

    private static boolean isTeamAdmin(UserAccount user, Team team) {
        var membership = TeamMembership.findByUserAndTeam(user, team);
        return membership != null && membership.role == UserRole.TEAM_ADMIN;
    }

    private static boolean isLegacyUnscoped(Agent agent) {
        return agent.tenant == null && agent.team == null && agent.ownerUser == null;
    }

    private static boolean isBootstrapAdmin(String username) {
        return adminUsername().equals(username) || "system".equals(username);
    }

    private static String adminUsername() {
        return Play.configuration.getProperty("jclaw.admin.username", "admin");
    }

    private static String normalizeUsername(String username) {
        var normalized = username == null ? "" : username.strip();
        return normalized.isEmpty() ? adminUsername() : normalized;
    }

    private static String sessionUsername() {
        try {
            var session = Scope.Session.current();
            return session == null ? null : session.get("username");
        } catch (RuntimeException _) {
            return null;
        }
    }
}
