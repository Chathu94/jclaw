package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import models.Team;
import models.Tenant;
import models.UserAccount;
import models.UserRole;
import play.mvc.Controller;
import play.mvc.With;
import services.AccessControlService;
import utils.ApiResponses;
import utils.JsonArgs;
import utils.PasswordHasher;

import java.time.Instant;

import static utils.GsonHolder.GSON;

@With(AuthCheck.class)
public class ApiAccessController extends Controller {

    private static final Gson gson = GSON;

    public record TenantView(Long id, String slug, String name, boolean enabled) {}
    public record TeamView(Long id, Long tenantId, String tenantSlug, String slug, String name, boolean enabled) {}
    public record UserView(Long id, String username, String displayName, String role,
                           Long tenantId, String tenantSlug, Long teamId, String teamSlug,
                           boolean enabled, boolean approved, boolean passwordSet,
                           Long approvedByUserId, String approvedAt) {}

    @Operation(summary = "List tenants visible to the current admin")
    public static void tenants() {
        var principal = AccessControlService.currentPrincipal();
        var rows = Tenant.<Tenant>findAll().stream()
                .filter(t -> principal.allAdmin()
                        || (principal.tenant() != null && principal.tenant().id.equals(t.id)))
                .map(ApiAccessController::tenantView)
                .toList();
        renderJSON(gson.toJson(rows));
    }

    @Operation(summary = "Create a tenant (all-admin only)")
    public static void createTenant() {
        requireAllAdmin();
        var body = requireBody();
        var slug = requireSlug(body);
        if (Tenant.findBySlug(slug) != null) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "Tenant slug already exists");
        }
        var tenant = new Tenant();
        tenant.slug = slug;
        tenant.name = JsonArgs.optString(body, "name", slug);
        var enabled = JsonArgs.optBoolean(body, "enabled");
        tenant.enabled = enabled == null || enabled;
        tenant.save();
        renderJSON(gson.toJson(tenantView(tenant)));
    }

    @Operation(summary = "List teams visible to the current admin")
    public static void teams() {
        var principal = AccessControlService.currentPrincipal();
        var rows = Team.<Team>findAll().stream()
                .filter(t -> canSeeTeam(principal, t))
                .map(ApiAccessController::teamView)
                .toList();
        renderJSON(gson.toJson(rows));
    }

    @Operation(summary = "Create a team under a tenant")
    public static void createTeam() {
        var body = requireBody();
        var tenant = requireTenant(JsonArgs.optLong(body, "tenantId"));
        var principal = AccessControlService.currentPrincipal();
        if (!principal.allAdmin()
                && (principal.role() != UserRole.TENANT_ADMIN
                || principal.tenant() == null || !principal.tenant().id.equals(tenant.id))) {
            forbidden();
        }
        var slug = requireSlug(body);
        if (Team.findBySlug(tenant, slug) != null) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "Team slug already exists in this tenant");
        }
        var team = new Team();
        team.tenant = tenant;
        team.slug = slug;
        team.name = JsonArgs.optString(body, "name", slug);
        var enabled = JsonArgs.optBoolean(body, "enabled");
        team.enabled = enabled == null || enabled;
        team.save();
        renderJSON(gson.toJson(teamView(team)));
    }

    @Operation(summary = "List users visible to the current admin")
    public static void users() {
        var principal = AccessControlService.currentPrincipal();
        var rows = UserAccount.<UserAccount>findAll().stream()
                .filter(u -> AccessControlService.canSee(principal, u.tenant, u.primaryTeam, u))
                .map(ApiAccessController::userView)
                .toList();
        renderJSON(gson.toJson(rows));
    }

    @Operation(summary = "Create a user in a tenant/team scope")
    public static void createUser() {
        var principal = AccessControlService.currentPrincipal();
        var body = requireBody();
        var username = JsonArgs.optString(body, "username", "");
        if (username.isBlank()) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "username is required");
        if (UserAccount.findByUsername(username) != null) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "Username already exists");
        }
        var tenant = requireTenant(JsonArgs.optLong(body, "tenantId"));
        var team = requireTeam(JsonArgs.optLong(body, "teamId"));
        if (!team.tenant.id.equals(tenant.id)) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "teamId must belong to tenantId");
        }
        var role = parseRole(JsonArgs.optString(body, "role", "USER"));
        requireCanCreateUser(principal, tenant, team, role);

        var user = new UserAccount();
        user.username = username.strip();
        user.displayName = JsonArgs.optString(body, "displayName", user.username);
        user.tenant = tenant;
        user.primaryTeam = team;
        user.role = role;
        var enabled = JsonArgs.optBoolean(body, "enabled");
        user.enabled = enabled == null || enabled;
        if (role == UserRole.USER) {
            user.approvedAt = Instant.now();
            user.approvedBy = principal.user();
        }
        var password = JsonArgs.optString(body, "password", "");
        if (!password.isBlank()) {
            ApiAuthController.validateNewPassword(password);
            user.passwordHash = PasswordHasher.hash(password);
            user.bumpCredentialVersion();
        }
        user.save();
        AccessControlService.ensureMembership(user, team, role);
        AccessControlService.ensurePersonalAgent(user);
        renderJSON(gson.toJson(userView(user)));
    }

    @Operation(summary = "Approve a pending admin user (all-admin only)")
    public static void approveUser(Long id) {
        requireAllAdmin();
        var user = UserAccount.<UserAccount>findById(id);
        if (user == null) ApiResponses.error(404, ApiResponses.NOT_FOUND, "No user with id " + id);
        if (user.role == UserRole.USER) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Only admin users require approval");
        }
        user.approvedAt = Instant.now();
        user.approvedBy = AccessControlService.currentUser();
        user.save();
        renderJSON(gson.toJson(userView(user)));
    }

    @Operation(summary = "Set a visible user's password")
    public static void setUserPassword(Long id) {
        var principal = AccessControlService.currentPrincipal();
        var user = UserAccount.<UserAccount>findById(id);
        if (user == null) ApiResponses.error(404, ApiResponses.NOT_FOUND, "No user with id " + id);
        if (!AccessControlService.canSee(principal, user.tenant, user.primaryTeam, user)) forbidden();
        var body = requireBody();
        var password = JsonArgs.optString(body, "password", "");
        if (password.isBlank()) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "password is required");
        ApiAuthController.validateNewPassword(password);
        user.passwordHash = PasswordHasher.hash(password);
        user.bumpCredentialVersion();
        user.save();
        renderJSON(gson.toJson(userView(user)));
    }

    private static void requireCanCreateUser(AccessControlService.Principal principal,
                                             Tenant tenant, Team team, UserRole role) {
        if (principal.allAdmin()) return;
        if (role == UserRole.ALL_ADMIN) forbidden();
        if (principal.role() == UserRole.TENANT_ADMIN
                && principal.tenant() != null && principal.tenant().id.equals(tenant.id)) return;
        if (principal.role() == UserRole.TEAM_ADMIN
                && role == UserRole.USER
                && principal.team() != null && principal.team().id.equals(team.id)) return;
        forbidden();
    }

    private static boolean canSeeTeam(AccessControlService.Principal principal, Team team) {
        if (principal.allAdmin()) return true;
        if (principal.role() == UserRole.TENANT_ADMIN
                && principal.tenant() != null && principal.tenant().id.equals(team.tenant.id)) return true;
        return principal.team() != null && principal.team().id.equals(team.id);
    }

    private static TenantView tenantView(Tenant t) {
        return new TenantView(t.id, t.slug, t.name, t.enabled);
    }

    private static TeamView teamView(Team t) {
        return new TeamView(t.id, t.tenant.id, t.tenant.slug, t.slug, t.name, t.enabled);
    }

    private static UserView userView(UserAccount u) {
        return new UserView(u.id, u.username, u.displayName, u.role.name(),
                u.tenant != null ? u.tenant.id : null,
                u.tenant != null ? u.tenant.slug : null,
                u.primaryTeam != null ? u.primaryTeam.id : null,
                u.primaryTeam != null ? u.primaryTeam.slug : null,
                u.enabled,
                u.isApprovedForAccess(),
                u.passwordHash != null && !u.passwordHash.isBlank(),
                u.approvedBy != null ? u.approvedBy.id : null,
                u.approvedAt != null ? u.approvedAt.toString() : null);
    }

    private static JsonObject requireBody() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();
        return body;
    }

    private static String requireSlug(JsonObject body) {
        var slug = JsonArgs.optString(body, "slug", "");
        if (slug.isBlank() || !slug.matches("[a-z0-9][a-z0-9_-]{0,79}")) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "slug must be 1-80 chars of lowercase letters, digits, hyphen, or underscore");
        }
        return slug;
    }

    private static Tenant requireTenant(Long id) {
        if (id == null) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "tenantId is required");
        var tenant = Tenant.<Tenant>findById(id);
        if (tenant == null) ApiResponses.error(404, ApiResponses.NOT_FOUND, "No tenant with id " + id);
        return tenant;
    }

    private static Team requireTeam(Long id) {
        if (id == null) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "teamId is required");
        var team = Team.<Team>findById(id);
        if (team == null) ApiResponses.error(404, ApiResponses.NOT_FOUND, "No team with id " + id);
        return team;
    }

    private static UserRole parseRole(String raw) {
        try {
            return UserRole.valueOf(raw == null ? "USER" : raw.strip().toUpperCase());
        } catch (IllegalArgumentException _) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Unknown role: " + raw);
            throw ApiResponses.unreachable();
        }
    }

    private static void requireAllAdmin() {
        if (!AccessControlService.currentPrincipal().allAdmin()) forbidden();
    }
}
