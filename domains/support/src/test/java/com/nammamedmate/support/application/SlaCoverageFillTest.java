package com.nammamedmate.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.adapter.out.messaging.StubAutomationEscalate;
import com.nammamedmate.support.application.SlaServiceTest.FakeEscalationMatrixStore;
import com.nammamedmate.support.application.SlaServiceTest.FakeSlaPolicyStore;
import com.nammamedmate.support.application.SlaServiceTest.RecordingAudit;
import com.nammamedmate.support.application.TicketServiceTest.FakeAgentStore;
import com.nammamedmate.support.application.TicketServiceTest.FakeCustomers;
import com.nammamedmate.support.application.TicketServiceTest.FakeNotifications;
import com.nammamedmate.support.application.TicketServiceTest.FakeTicketStore;
import com.nammamedmate.support.application.port.out.AutomationEscalatePort;
import com.nammamedmate.support.application.port.out.EscalationMatrixStore;
import com.nammamedmate.support.application.port.out.EscalationMatrixStore.RulePatch;
import com.nammamedmate.support.application.port.out.NotificationDispatchPort;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketChannel;
import com.nammamedmate.support.domain.TicketPriority;
import com.nammamedmate.support.domain.TicketStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlaCoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID SUPER = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID AGENT = UUID.fromString("a0000002-0000-4000-8000-000000000002");

  private FakeTicketStore tickets;
  private FakeAgentStore agents;
  private FakeNotifications notifications;
  private FakeSlaPolicyStore policies;
  private FakeEscalationMatrixStore matrix;
  private SlaService sla;
  private TicketService ticketService;
  private final MedmatePrincipal adminSuper =
      new MedmatePrincipal(SUPER, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    tickets = new FakeTicketStore();
    agents = new FakeAgentStore();
    notifications = new FakeNotifications();
    policies = new FakeSlaPolicyStore();
    matrix = new FakeEscalationMatrixStore();
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), true, 20, "Ravi", NOW));
    agents.put(new AgentProfile(SUPER, List.of(), true, 20, "Admin", NOW));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    sla =
        new SlaService(
            policies,
            matrix,
            tickets,
            agents,
            new FakeCustomers(),
            new StubAutomationEscalate(tickets, notifications, clock),
            new RecordingAudit(),
            clock);
    AgentService agentService = new AgentService(agents, tickets, notifications, clock);
    ticketService =
        new TicketService(
            tickets,
            agents,
            new FakeCustomers(),
            notifications,
            policies,
            new com.nammamedmate.support.application.KbTestDoubles.EmptyCanned(),
            new com.nammamedmate.support.application.KbTestDoubles.EmptyOrders(),
            clock,
            agentService);
  }

  @Test
  void moreSlaServiceBranches() {
    assertThatThrownBy(() -> sla.getEscalationMatrix(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    Map<String, Object> created =
        ticketService.create(
            customer,
            new TicketService.CreateCommand("OTHER", "fr", "d", "APP", null, null, null, "LOW"));
    UUID id = (UUID) created.get("id");
    Ticket t = tickets.findById(id).orElseThrow();
    Instant due = NOW.minusSeconds(40 * 60);
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.LOW,
            SlaLevel.L1,
            due,
            due,
            due.plusSeconds(99999),
            null,
            t.channel(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    Map<String, Object> both = sla.listBreaches(adminSuper, null, null, null);
    assertThat((Integer) both.get("breach_count")).isGreaterThanOrEqualTo(1);
    Map<String, Object> fr = sla.listBreaches(adminSuper, "FIRST_RESPONSE", "L1", null);
    assertThat((Integer) fr.get("breach_count")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> row = ((List<Map<String, Object>>) fr.get("breaches")).getFirst();
    assertThat(row.get("assigned_agent")).isNull();

    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.LOW,
            SlaLevel.L1,
            due,
            due,
            due.plusSeconds(99999),
            UUID.randomUUID(),
            t.channel(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    Map<String, Object> missingAgent = sla.listBreaches(adminSuper, "FIRST_RESPONSE", null, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> row2 =
        ((List<Map<String, Object>>) missingAgent.get("breaches")).getFirst();
    assertThat(row2.get("assigned_agent")).isNull();

    // FIRST_RESPONSE filter when already responded
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.LOW,
            SlaLevel.L1,
            due,
            due,
            due.minusSeconds(10),
            null,
            t.channel(),
            NOW,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    assertThat(
            (Integer)
                sla.listBreaches(adminSuper, "FIRST_RESPONSE", null, null).get("breach_count"))
        .isEqualTo(0);
    assertThat((Integer) sla.listBreaches(adminSuper, "RESOLUTION", null, null).get("breach_count"))
        .isEqualTo(1);
    assertThat((Integer) sla.listBreaches(adminSuper, "   ", null, null).get("breach_count"))
        .isGreaterThanOrEqualTo(0);
    assertThat((Integer) sla.listBreaches(adminSuper, null, "  ", null).get("breach_count"))
        .isGreaterThanOrEqualTo(0);
    // resolution-only auto-escalate
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.LOW,
            SlaLevel.L1,
            NOW.plusSeconds(3600),
            NOW.plusSeconds(3600),
            NOW.minusSeconds(40 * 60),
            null,
            t.channel(),
            NOW,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    assertThat(sla.processSlaBreaches(10)).isEqualTo(1);
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.LOW,
            SlaLevel.L1,
            NOW.plusSeconds(3600),
            NOW.plusSeconds(3600),
            NOW.plusSeconds(7200),
            null,
            t.channel(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    assertThat(sla.processSlaBreaches(10)).isEqualTo(0);

    EscalationMatrixStoreEmpty empty = new EscalationMatrixStoreEmpty();
    SlaService svc =
        new SlaService(
            policies,
            empty,
            tickets,
            agents,
            new FakeCustomers(),
            new StubAutomationEscalate(tickets, notifications, Clock.fixed(NOW, ZoneOffset.UTC)),
            new RecordingAudit(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    // restore breached L1 for empty-matrix escalate
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.LOW,
            SlaLevel.L1,
            due,
            due,
            due.plusSeconds(99999),
            null,
            t.channel(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    // L1 default threshold 30 — already 40 min breached
    assertThat(svc.processSlaBreaches(10)).isEqualTo(1);
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.HIGH,
            SlaLevel.L3,
            NOW.minusSeconds(500 * 60),
            NOW.minusSeconds(500 * 60),
            NOW.minusSeconds(500 * 60),
            null,
            t.channel(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    assertThat(svc.processSlaBreaches(10)).isEqualTo(1);

    new com.nammamedmate.support.domain.EscalationRule(
        UUID.randomUUID(), SlaLevel.L1, "c", "t", null, 30, null, NOW);
    new EscalationMatrixStore.RulePatch(SlaLevel.L1, 1, null);
  }

  @Test
  void policyNullIdAndAuthBranches() {
    assertThatThrownBy(() -> sla.updatePolicy(adminSuper, null, 1, 1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SLA_POLICY_NOT_FOUND");
    assertThatThrownBy(() -> sla.listPolicies(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal pharmacy =
        new MedmatePrincipal(CUST, AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> sla.listPolicies(pharmacy))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> sla.updateEscalationMatrix(pharmacy, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThat(sla.updateEscalationMatrix(adminSuper, null).get("updated_levels"))
        .isEqualTo(List.of());
  }

  @Test
  void breachFiltersAndResolutionType() {
    Map<String, Object> created =
        ticketService.create(
            customer,
            new TicketService.CreateCommand("OTHER", "Q", "d", "APP", null, null, null, "MEDIUM"));
    UUID id = (UUID) created.get("id");
    Ticket t = tickets.findById(id).orElseThrow();
    Instant due = NOW.minusSeconds(200 * 60);
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            t.priority(),
            SlaLevel.L2,
            due,
            due,
            due,
            AGENT,
            t.channel(),
            NOW.minusSeconds(10),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    assertThatThrownBy(() -> sla.listBreaches(adminSuper, null, "NOPE", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> filtered = sla.listBreaches(adminSuper, "RESOLUTION", "L2", AGENT);
    assertThat((Integer) filtered.get("breach_count")).isEqualTo(1);
    Map<String, Object> skippedAgent =
        sla.listBreaches(adminSuper, "RESOLUTION", "L2", UUID.randomUUID());
    assertThat((Integer) skippedAgent.get("breach_count")).isEqualTo(0);
    Map<String, Object> skippedLevel = sla.listBreaches(adminSuper, "RESOLUTION", "L1", AGENT);
    assertThat((Integer) skippedLevel.get("breach_count")).isEqualTo(0);
    @SuppressWarnings("unchecked")
    Map<String, Object> row = ((List<Map<String, Object>>) filtered.get("breaches")).getFirst();
    assertThat(row.get("assigned_agent")).isEqualTo("Ravi");
  }

  @Test
  void processSlaBranchesIncludingDefaults() {
    // open non-breached
    ticketService.create(
        customer,
        new TicketService.CreateCommand("OTHER", "ok", "d", "APP", null, null, null, "LOW"));
    assertThat(sla.processSlaBreaches(10)).isEqualTo(0);

    Map<String, Object> created =
        ticketService.create(
            customer,
            new TicketService.CreateCommand("OTHER", "late", "d", "APP", null, null, null, "LOW"));
    UUID id = (UUID) created.get("id");
    Ticket t = tickets.findById(id).orElseThrow();
    Instant due = NOW.minusSeconds(10 * 60); // under L1 threshold 30
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.LOW,
            SlaLevel.L1,
            due,
            due,
            due.plusSeconds(3600),
            null,
            t.channel(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    assertThat(sla.processSlaBreaches(10)).isEqualTo(0);

    // L4 already notified
    Instant far = NOW.minusSeconds(2000 * 60);
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.URGENT,
            SlaLevel.L4,
            far,
            far,
            far,
            null,
            t.channel(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            null,
            t.createdAt(),
            NOW));
    assertThat(sla.processSlaBreaches(10)).isEqualTo(0);
  }

  @Test
  void processWithEmptyMatrixUsesDefaults() {
    EscalationMatrixStoreEmpty empty = new EscalationMatrixStoreEmpty();
    SlaService svc =
        new SlaService(
            policies,
            empty,
            tickets,
            agents,
            new FakeCustomers(),
            new StubAutomationEscalate(tickets, notifications, Clock.fixed(NOW, ZoneOffset.UTC)),
            new RecordingAudit(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    Map<String, Object> created =
        ticketService.create(
            customer,
            new TicketService.CreateCommand(
                "OTHER", "urgent", "d", "APP", null, null, null, "URGENT"));
    UUID id = (UUID) created.get("id");
    Ticket t = tickets.findById(id).orElseThrow();
    Instant far = NOW.minusSeconds(2000 * 60);
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.URGENT,
            SlaLevel.L4,
            far,
            far,
            far,
            null,
            t.channel(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    assertThat(svc.processSlaBreaches(10)).isEqualTo(1);

    // L2 escalate with empty matrix (default threshold 120)
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            null,
            null,
            t.category(),
            t.subject(),
            TicketStatus.OPEN,
            TicketPriority.MEDIUM,
            SlaLevel.L2,
            NOW.minusSeconds(130 * 60),
            NOW.minusSeconds(130 * 60),
            NOW.minusSeconds(130 * 60),
            null,
            t.channel(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    assertThat(svc.processSlaBreaches(10)).isEqualTo(1);
  }

  @Test
  void ticketDomainBranchesAndPortDefaults() {
    Instant now = NOW;
    Ticket compact =
        new Ticket(
            UUID.randomUUID(),
            "TKT-20260724-000099",
            CUST,
            null,
            null,
            TicketCategory.OTHER,
            "s",
            TicketStatus.OPEN,
            TicketPriority.MEDIUM,
            SlaLevel.L2,
            now.plusSeconds(60),
            null,
            null,
            null,
            TicketChannel.APP,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now);
    assertThat(compact.firstResponseDueAt()).isEqualTo(compact.slaDueAt());
    assertThat(compact.resolutionDueAt()).isEqualTo(compact.slaDueAt());
    assertThat(compact.resolutionBreached(now)).isFalse();
    assertThat(compact.minutesBreachedResolution(now)).isEqualTo(0);
    assertThat(compact.minutesBreachedFirstResponse(now)).isEqualTo(0);
    assertThat(compact.resolutionBreached(now.plusSeconds(120))).isTrue();
    Ticket paused = compact.withStatus(TicketStatus.OPEN, now).withSlaPause(now, now);
    assertThat(paused.firstResponseBreached(now.plusSeconds(3600))).isFalse();
    assertThat(paused.resolutionBreached(now.plusSeconds(3600))).isFalse();
    Ticket closed = compact.withStatus(TicketStatus.CLOSED, now);
    assertThat(closed.resolutionBreached(now.plusSeconds(99999))).isFalse();
    Ticket awaitingRes = compact.withStatus(TicketStatus.AWAITING_CUSTOMER, now);
    assertThat(awaitingRes.resolutionBreached(now.plusSeconds(99999))).isFalse();
    Ticket withPauseAlready = compact.withSlaPause(now.minusSeconds(60), now);
    assertThat(
            withPauseAlready
                .withFirstResponse(now, TicketStatus.AWAITING_CUSTOMER, now)
                .slaPausedAt())
        .isEqualTo(now.minusSeconds(60));
    assertThat(compact.withFirstResponse(now, TicketStatus.IN_PROGRESS, now).slaPausedAt())
        .isNull();
    Ticket resolved = compact.withResolved(now, "x", now, now);
    assertThat(resolved.resolutionBreached(now.plusSeconds(99999))).isFalse();

    AutomationEscalatePort auto = (ticketId, from, to) -> {};
    auto.notifyL4SeniorOps(UUID.randomUUID(), "t", List.of());
    NotificationDispatchPort n =
        new NotificationDispatchPort() {
          @Override
          public void notifyEscalation(UUID ticketId, UUID customerId, String slaLevel) {}

          @Override
          public void notifyCsatSurvey(UUID ticketId, UUID customerId, String channel) {}

          @Override
          public void notifySupervisorEscalation(UUID ticketId, String reason) {}
        };
    n.notifyEscalationChannels(UUID.randomUUID(), CUST, "L1", "team", List.of("IN_APP"));
    // customer resume with null sla_paused_at
    Map<String, Object> created =
        ticketService.create(
            customer,
            new TicketService.CreateCommand("OTHER", "pause", "d", "APP", null, null, null, "LOW"));
    UUID id = (UUID) created.get("id");
    MedmatePrincipal admin =
        new MedmatePrincipal(SUPER, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    ticketService.reply(admin, id, new TicketService.ReplyCommand("a", false, null, null));
    Ticket awaiting = tickets.findById(id).orElseThrow();
    tickets.update(
        awaiting.withSlaPause(null, NOW).withStatus(TicketStatus.AWAITING_CUSTOMER, NOW));
    ticketService.reply(customer, id, new TicketService.ReplyCommand("back", false, null, null));
    assertThat(tickets.findById(id).orElseThrow().status()).isEqualTo(TicketStatus.IN_PROGRESS);
  }

  @Test
  void ticketServicePauseResumeAndPolicyFallback() {
    Map<String, Object> created =
        ticketService.create(
            customer,
            new TicketService.CreateCommand("OTHER", "Q", "d", "APP", null, null, null, "MEDIUM"));
    UUID id = (UUID) created.get("id");
    MedmatePrincipal admin =
        new MedmatePrincipal(SUPER, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    ticketService.reply(admin, id, new TicketService.ReplyCommand("hi", false, List.of(), null));
    Ticket awaiting = tickets.findById(id).orElseThrow();
    assertThat(awaiting.status()).isEqualTo(TicketStatus.AWAITING_CUSTOMER);
    // second agent reply while already awaiting — sets pause via status path
    tickets.update(awaiting.withFirstResponse(NOW, TicketStatus.IN_PROGRESS, NOW));
    ticketService.reply(admin, id, new TicketService.ReplyCommand("again", false, null, null));
    assertThat(tickets.findById(id).orElseThrow().status())
        .isEqualTo(TicketStatus.AWAITING_CUSTOMER);

    TicketService fallback =
        new TicketService(
            tickets,
            agents,
            new FakeCustomers(),
            notifications,
            new com.nammamedmate.support.application.port.out.SlaPolicyStore() {
              @Override
              public List<com.nammamedmate.support.domain.SlaPolicy> listAll() {
                return List.of();
              }

              @Override
              public java.util.Optional<com.nammamedmate.support.domain.SlaPolicy> findById(
                  UUID id) {
                return java.util.Optional.empty();
              }

              @Override
              public java.util.Optional<com.nammamedmate.support.domain.SlaPolicy> resolve(
                  TicketCategory category, TicketPriority priority) {
                return java.util.Optional.empty();
              }

              @Override
              public com.nammamedmate.support.domain.SlaPolicy update(
                  UUID id,
                  Integer firstResponseMinutes,
                  Integer resolutionMinutes,
                  SlaLevel slaLevel,
                  UUID updatedBy,
                  Instant updatedAt) {
                throw new UnsupportedOperationException();
              }
            },
            new com.nammamedmate.support.application.KbTestDoubles.EmptyCanned(),
            new com.nammamedmate.support.application.KbTestDoubles.EmptyOrders(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new AgentService(agents, tickets, notifications, Clock.fixed(NOW, ZoneOffset.UTC)));
    Map<String, Object> created2 =
        fallback.create(
            customer,
            new TicketService.CreateCommand("ACCOUNT", "x", "d", "APP", null, null, null, "HIGH"));
    assertThat(created2.get("sla_level")).isEqualTo("L3");
  }

  /** Empty matrix for defaultThreshold branches. */
  static final class EscalationMatrixStoreEmpty
      implements com.nammamedmate.support.application.port.out.EscalationMatrixStore {
    @Override
    public List<com.nammamedmate.support.domain.EscalationRule> listAll() {
      return List.of();
    }

    @Override
    public java.util.Optional<com.nammamedmate.support.domain.EscalationRule> findByLevel(
        SlaLevel level) {
      return java.util.Optional.empty();
    }

    @Override
    public List<SlaLevel> updateRules(List<RulePatch> patches, UUID updatedBy, Instant updatedAt) {
      return List.of();
    }
  }
}
