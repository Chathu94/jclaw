import models.Agent;
import models.AgentSkillConfig;
import models.AgentToolConfig;
import models.Memory;
import models.Team;
import models.Tenant;
import models.UserAccount;
import models.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AccessControlService;
import services.AgentService;

import java.util.List;

class AccessControlServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void creatingAnAgentOutsideARequestStampsDefaultScope() {
        var agent = AgentService.create("scoped-default", "openrouter", "gpt-4.1");

        assertNotNull(agent.tenant);
        assertNotNull(agent.team);
        assertNotNull(agent.ownerUser);
        assertEquals(Tenant.DEFAULT_SLUG, agent.tenant.slug);
        assertEquals(Team.DEFAULT_SLUG, agent.team.slug);
        assertEquals(UserRole.ALL_ADMIN, agent.ownerUser.role);
    }

    @Test
    void roleHierarchyCanSeeOnlyDownItsScope() {
        var tenantA = tenant("tenant-a");
        var tenantB = tenant("tenant-b");
        var teamA1 = team(tenantA, "team-a1");
        var teamA2 = team(tenantA, "team-a2");
        var teamB1 = team(tenantB, "team-b1");

        var allAdmin = user("all-admin", UserRole.ALL_ADMIN, null, null);
        allAdmin.approvedAt = java.time.Instant.now();
        allAdmin.save();
        var tenantAdmin = user("tenant-admin", UserRole.TENANT_ADMIN, tenantA, teamA1);
        tenantAdmin.approvedAt = java.time.Instant.now();
        tenantAdmin.save();
        var teamAdmin = user("team-admin", UserRole.TEAM_ADMIN, tenantA, teamA1);
        teamAdmin.approvedAt = java.time.Instant.now();
        teamAdmin.save();
        AccessControlService.ensureMembership(teamAdmin, teamA1, UserRole.TEAM_ADMIN);
        var userA1 = user("user-a1", UserRole.USER, tenantA, teamA1);
        var userA2 = user("user-a2", UserRole.USER, tenantA, teamA2);
        var userB1 = user("user-b1", UserRole.USER, tenantB, teamB1);

        assertTrue(AccessControlService.canSee(principal(allAdmin), tenantA, teamA1, userA1));
        assertTrue(AccessControlService.canSee(principal(allAdmin), tenantB, teamB1, userB1));

        assertTrue(AccessControlService.canSee(principal(tenantAdmin), tenantA, teamA1, userA1));
        assertTrue(AccessControlService.canSee(principal(tenantAdmin), tenantA, teamA2, userA2));
        assertFalse(AccessControlService.canSee(principal(tenantAdmin), tenantB, teamB1, userB1));

        assertTrue(AccessControlService.canSee(principal(teamAdmin), tenantA, teamA1, userA1));
        assertFalse(AccessControlService.canSee(principal(teamAdmin), tenantA, teamA2, userA2));
        assertFalse(AccessControlService.canSee(principal(teamAdmin), tenantB, teamB1, userB1));

        assertTrue(AccessControlService.canSee(principal(userA1), tenantA, teamA1, userA1));
        assertFalse(AccessControlService.canSee(principal(userA1), tenantA, teamA1, teamAdmin));
        assertFalse(AccessControlService.canSee(principal(userA1), tenantA, teamA2, userA2));
    }

    @Test
    void subagentsInheritTheirParentsScope() {
        var parent = AgentService.create("scope-parent", "openrouter", "gpt-4.1");
        var child = AgentService.create("scope-child", "openrouter", "gpt-4.1",
                null, null, false, parent);

        assertEquals(parent.tenant.id, child.tenant.id);
        assertEquals(parent.team.id, child.team.id);
        assertEquals(parent.ownerUser.id, child.ownerUser.id);
    }

    @Test
    void personalDefaultAgentsUseSeparateWorkspaces() {
        AgentService.create(Agent.MAIN_AGENT_NAME, "openrouter", "gpt-4.1");
        var tenant = tenant("personal-tenant");
        var team = team(tenant, "personal-team");
        var userOne = user("personal-one", UserRole.USER, tenant, team);
        var userTwo = user("personal-two", UserRole.USER, tenant, team);

        var agentOne = AccessControlService.ensurePersonalAgent(userOne);
        var agentTwo = AccessControlService.ensurePersonalAgent(userTwo);

        assertNotEquals(agentOne.name, agentTwo.name);
        assertNotEquals(AgentService.workspacePath(agentOne.name), AgentService.workspacePath(agentTwo.name));

        AgentService.writeWorkspaceFile(agentOne.name, "AGENT.md", "user one instructions");
        AgentService.writeWorkspaceFile(agentTwo.name, "AGENT.md", "user two instructions");

        assertEquals("user one instructions", AgentService.readWorkspaceFile(agentOne.name, "AGENT.md"));
        assertEquals("user two instructions", AgentService.readWorkspaceFile(agentTwo.name, "AGENT.md"));
    }

    @Test
    void readableAgentFilterSeparatesTenantAndTeamResources() {
        var tenantA = tenant("scope-a");
        var tenantB = tenant("scope-b");
        var teamA1 = team(tenantA, "scope-a1");
        var teamA2 = team(tenantA, "scope-a2");
        var teamB1 = team(tenantB, "scope-b1");
        var tenantAdmin = user("scope-tenant-admin", UserRole.TENANT_ADMIN, tenantA, teamA1);
        tenantAdmin.approvedAt = java.time.Instant.now();
        tenantAdmin.save();
        var teamAdmin = user("scope-team-admin", UserRole.TEAM_ADMIN, tenantA, teamA1);
        teamAdmin.approvedAt = java.time.Instant.now();
        teamAdmin.save();
        AccessControlService.ensureMembership(teamAdmin, teamA1, UserRole.TEAM_ADMIN);
        var ownerA1 = user("scope-user-a1", UserRole.USER, tenantA, teamA1);
        var ownerA2 = user("scope-user-a2", UserRole.USER, tenantA, teamA2);
        var ownerB1 = user("scope-user-b1", UserRole.USER, tenantB, teamB1);
        var agentA1 = scopedAgent("agent-a1", tenantA, teamA1, ownerA1);
        var agentA2 = scopedAgent("agent-a2", tenantA, teamA2, ownerA2);
        var agentB1 = scopedAgent("agent-b1", tenantB, teamB1, ownerB1);

        assertEquals(List.of(agentA1.id, agentA2.id),
                AccessControlService.filterReadableAgents(principal(tenantAdmin),
                                List.of(agentA1, agentA2, agentB1))
                        .stream().map(a -> a.id).toList());
        assertEquals(List.of(agentA1.id),
                AccessControlService.filterReadableAgents(principal(teamAdmin),
                                List.of(agentA1, agentA2, agentB1))
                        .stream().map(a -> a.id).toList());
        assertEquals(List.of(agentA2.id),
                AccessControlService.filterReadableAgents(principal(ownerA2),
                                List.of(agentA1, agentA2, agentB1))
                        .stream().map(a -> a.id).toList());
    }

    @Test
    void toolsSkillsAndMemoryStayBehindReadableAgentBoundary() {
        var tenant = tenant("resource-tenant");
        var teamOne = team(tenant, "resource-one");
        var teamTwo = team(tenant, "resource-two");
        var teamAdmin = user("resource-team-admin", UserRole.TEAM_ADMIN, tenant, teamOne);
        teamAdmin.approvedAt = java.time.Instant.now();
        teamAdmin.save();
        AccessControlService.ensureMembership(teamAdmin, teamOne, UserRole.TEAM_ADMIN);
        var ownerOne = user("resource-user-one", UserRole.USER, tenant, teamOne);
        var ownerTwo = user("resource-user-two", UserRole.USER, tenant, teamTwo);
        var visible = scopedAgent("resource-visible", tenant, teamOne, ownerOne);
        var hidden = scopedAgent("resource-hidden", tenant, teamTwo, ownerTwo);
        addTool(visible, "exec");
        addTool(hidden, "browser");
        addSkill(visible, "reports");
        addSkill(hidden, "payroll");
        addMemory(visible, "visible memory");
        addMemory(hidden, "hidden memory");

        var readableIds = AccessControlService.filterReadableAgents(principal(teamAdmin),
                        Agent.<Agent>findAll())
                .stream().map(a -> a.id).toList();

        assertTrue(readableIds.contains(visible.id));
        assertFalse(readableIds.contains(hidden.id));
        assertEquals(1, AgentToolConfig.find("agent.id IN ?1", readableIds).fetch().size());
        assertEquals(1, AgentSkillConfig.find("agent.id IN ?1", readableIds).fetch().size());
        assertEquals(1, Memory.find("agent.id IN ?1", readableIds).fetch().size());
    }

    @Test
    void pendingAdminsDoNotReceiveElevatedAccessUntilAllAdminApproves() {
        var tenant = tenant("pending-tenant");
        var teamOne = team(tenant, "pending-one");
        var teamTwo = team(tenant, "pending-two");
        var pendingTenantAdmin = user("pending-tenant-admin", UserRole.TENANT_ADMIN, tenant, teamOne);
        var ownerTwo = user("pending-user-two", UserRole.USER, tenant, teamTwo);
        var hiddenUntilApproved = scopedAgent("pending-agent", tenant, teamTwo, ownerTwo);

        assertFalse(AccessControlService.canReadAgent(principal(pendingTenantAdmin), hiddenUntilApproved));

        pendingTenantAdmin.approvedAt = java.time.Instant.now();
        pendingTenantAdmin.save();

        assertTrue(AccessControlService.canReadAgent(principal(pendingTenantAdmin), hiddenUntilApproved));
    }

    private static AccessControlService.Principal principal(UserAccount user) {
        return new AccessControlService.Principal(user, user.role, user.tenant, user.primaryTeam, false);
    }

    private static Tenant tenant(String slug) {
        var t = new Tenant();
        t.slug = slug;
        t.name = slug;
        t.enabled = true;
        t.save();
        return t;
    }

    private static Team team(Tenant tenant, String slug) {
        var team = new Team();
        team.tenant = tenant;
        team.slug = slug;
        team.name = slug;
        team.enabled = true;
        team.save();
        return team;
    }

    private static UserAccount user(String username, UserRole role, Tenant tenant, Team team) {
        var user = new UserAccount();
        user.username = username;
        user.displayName = username;
        user.role = role;
        user.tenant = tenant;
        user.primaryTeam = team;
        user.enabled = true;
        user.save();
        if (team != null) AccessControlService.ensureMembership(user, team, role);
        return user;
    }

    private static Agent scopedAgent(String name, Tenant tenant, Team team, UserAccount owner) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.tenant = tenant;
        agent.team = team;
        agent.ownerUser = owner;
        agent.save();
        return agent;
    }

    private static void addTool(Agent agent, String name) {
        var cfg = new AgentToolConfig();
        cfg.agent = agent;
        cfg.toolName = name;
        cfg.enabled = true;
        cfg.save();
    }

    private static void addSkill(Agent agent, String name) {
        var cfg = new AgentSkillConfig();
        cfg.agent = agent;
        cfg.skillName = name;
        cfg.enabled = true;
        cfg.save();
    }

    private static void addMemory(Agent agent, String text) {
        var memory = new Memory();
        memory.agent = agent;
        memory.text = text;
        memory.importance = 0.5;
        memory.save();
    }
}
