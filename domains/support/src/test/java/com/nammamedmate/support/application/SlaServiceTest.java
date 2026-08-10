package com.nammamedmate.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.adapter.out.messaging.StubAutomationEscalate;
import com.nammamedmate.support.application.TicketServiceTest.FakeAgentStore;
import com.nammamedmate.support.application.TicketServiceTest.FakeCustomers;
import com.nammamedmate.support.application.TicketServiceTest.FakeNotifications;
import com.nammamedmate.support.application.TicketServiceTest.FakeTicketStore;
import com.nammamedmate.support.application.port.out.EscalationMatrixStore;
import com.nammamedmate.support.application.port.out.EscalationMatrixStore.RulePatch;
import com.nammamedmate.support.application.port.out.SlaPolicyStore;
import com.nammamedmate.support.application.port.out.SupportAuditPort;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.EscalationRule;
import com.nammamedmate.support.domain.SlaAdherence;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.SlaPolicy;
import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketPriority;
import com.nammamedmate.support.domain.TicketStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlaServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID SUPER = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID OPS = UUID.fromString("a0000003-0000-4000-8000-000000000003");
  private static final UUID AGENT = UUID.fromString("a0000002-0000-4000-8000-000000000002");

  private FakeTicketStore tickets;
  private FakeAgentStore agents;
  private FakeNotifications notifications;
  private FakeSlaPolicyStore policies;
  private FakeEscalationMatrixStore matrix;
  private RecordingAudit audit;
  private SlaService sla;
  private TicketService ticketsSvc;

  private final MedmatePrincipal adminSuper =
      new MedmatePrincipal(SUPER, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal adminOps =
      new MedmatePrincipal(OPS, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal adminSupport =
      new MedmatePrincipal(SUPER, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    tickets = new FakeTicketStore();
    agents = new FakeAgentStore();
    notifications = new FakeNotifications();
    policies = new FakeSlaPolicyStore();
    matrix = new FakeEscalationMatrixStore();
    audit = new RecordingAudit();
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), true, 20, "Ravi Kumar", NOW));
    agents.put(new AgentProfile(SUPER, List.of(), true, 20, "Admin", NOW));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    StubAutomationEscalate automation = new StubAutomationEscalate(tickets, notifications, clock);
    sla =
        new SlaService(
            policies, matrix, tickets, agents, new FakeCustomers(), automation, audit, clock);
    AgentService agentService = new AgentService(agents, tickets, notifications, clock);
    ticketsSvc =
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
  void ac001_orderAnyPolicyDefaults() {
    SlaPolicy orderAny =
        policies.listAll().stream()
            .filter(p -> "ORDER".equals(p.category()) && "ANY".equals(p.priority()))
            .findFirst()
            .orElseThrow();
    assertThat(orderAny.firstResponseSlaMinutes()).isEqualTo(30);
    assertThat(orderAny.slaLevel()).isEqualTo(SlaLevel.L1);
    SlaPolicy effective = policies.resolve(TicketCategory.ORDER, TicketPriority.HIGH).orElseThrow();
    assertThat(effective.firstResponseSlaMinutes()).isEqualTo(30);
    assertThat(effective.slaLevel()).isEqualTo(SlaLevel.L1);
  }

  @Test
  void ac002_updatePolicyAuditLoggedAndAppliesToNewTickets() {
    SlaPolicy orderAny =
        policies.listAll().stream()
            .filter(p -> "ORDER".equals(p.category()))
            .findFirst()
            .orElseThrow();
    Map<String, Object> updated = sla.updatePolicy(adminSuper, orderAny.id(), 45, null);
    assertThat(updated.get("first_response_sla_minutes")).isEqualTo(45);
    assertThat(audit.actions).contains("SLA_POLICY_UPDATED");
    Map<String, Object> created =
        ticketsSvc.create(
            customer,
            new TicketService.CreateCommand(
                "ORDER", "Wrong items", "desc", "APP", null, null, null, null));
    assertThat(created.get("sla_level")).isEqualTo("L1");
    assertThat(created.get("sla_due_at")).isEqualTo(NOW.plusSeconds(45 * 60));
  }

  @Test
  void ac003_breachMinutesIncreaseLive() {
    Map<String, Object> created =
        ticketsSvc.create(
            customer,
            new TicketService.CreateCommand(
                "ORDER", "Wrong items", "desc", "APP", null, null, null, "HIGH"));
    UUID id = (UUID) created.get("id");
    Ticket t = tickets.findById(id).orElseThrow();
    tickets.update(
        new Ticket(
            t.id(),
            t.ticketId(),
            t.customerId(),
            t.pharmacyId(),
            t.orderId(),
            t.category(),
            t.subject(),
            t.status(),
            t.priority(),
            t.slaLevel(),
            NOW.minusSeconds(120 * 60),
            NOW.minusSeconds(120 * 60),
            t.resolutionDueAt(),
            AGENT,
            t.channel(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            t.createdByAdminId(),
            null,
            null,
            null,
            t.createdAt(),
            NOW));
    SlaService atBreach =
        new SlaService(
            policies,
            matrix,
            tickets,
            agents,
            new FakeCustomers(),
            new StubAutomationEscalate(tickets, notifications, Clock.fixed(NOW, ZoneOffset.UTC)),
            audit,
            Clock.fixed(NOW, ZoneOffset.UTC));
    Map<String, Object> first = atBreach.listBreaches(adminSupport, "FIRST_RESPONSE", null, null);
    assertThat((Integer) first.get("breach_count")).isGreaterThan(0);
    long m1 =
        ((Number)
                ((List<Map<String, Object>>) first.get("breaches"))
                    .getFirst()
                    .get("minutes_breached"))
            .longValue();
    SlaService later =
        new SlaService(
            policies,
            matrix,
            tickets,
            agents,
            new FakeCustomers(),
            new StubAutomationEscalate(
                tickets, notifications, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC)),
            audit,
            Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
    Map<String, Object> second = later.listBreaches(adminSupport, "FIRST_RESPONSE", null, null);
    long m2 =
        ((Number)
                ((List<Map<String, Object>>) second.get("breaches"))
                    .getFirst()
                    .get("minutes_breached"))
            .longValue();
    assertThat(m2).isGreaterThan(m1);
  }

  @Test
  void ac004_l1AutoEscalatesToL2AfterThreshold() {
    Map<String, Object> created =
        ticketsSvc.create(
            customer,
            new TicketService.CreateCommand(
                "ORDER", "Wrong items", "desc", "APP", null, null, null, null));
    UUID id = (UUID) created.get("id");
    Ticket t = tickets.findById(id).orElseThrow();
    Instant due = NOW.minusSeconds(35 * 60);
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
            SlaLevel.L1,
            due,
            due,
            t.resolutionDueAt(),
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
    assertThat(sla.processSlaBreaches(10)).isEqualTo(1);
    assertThat(tickets.findById(id).orElseThrow().slaLevel()).isEqualTo(SlaLevel.L2);
  }

  @Test
  void ac005_escalationMatrixHasFourLevels() {
    Map<String, Object> data = sla.getEscalationMatrix(adminOps);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("escalation_matrix");
    assertThat(rows).hasSize(4);
    assertThat(rows.getFirst().get("notification_channel")).isEqualTo(List.of("IN_APP"));
    assertThat(rows.get(3).get("notification_channel"))
        .isEqualTo(List.of("IN_APP", "WHATSAPP", "CALL"));
  }

  @Test
  void ac006_updateMatrixPersistsEmailChannel() {
    sla.updateEscalationMatrix(
        adminSuper,
        List.of(new RulePatch(SlaLevel.L2, 90, List.of("IN_APP", "WHATSAPP", "EMAIL"))));
    Map<String, Object> data = sla.getEscalationMatrix(adminOps);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("escalation_matrix");
    Map<String, Object> l2 =
        rows.stream().filter(r -> "L2".equals(r.get("level"))).findFirst().orElseThrow();
    assertThat(l2.get("notification_channel")).isEqualTo(List.of("IN_APP", "WHATSAPP", "EMAIL"));
    assertThat(l2.get("auto_escalate_after_minutes")).isEqualTo(90);
  }

  @Test
  void ac007_awaitingCustomerPausesBreachGrowth() {
    Map<String, Object> created =
        ticketsSvc.create(
            customer,
            new TicketService.CreateCommand("OTHER", "Q", "d", "APP", null, null, null, "MEDIUM"));
    UUID id = (UUID) created.get("id");
    ticketsSvc.reply(
        adminSupport, id, new TicketService.ReplyCommand("looking", false, List.of(), null));
    Ticket paused = tickets.findById(id).orElseThrow();
    assertThat(paused.status()).isEqualTo(TicketStatus.AWAITING_CUSTOMER);
    assertThat(paused.slaPausedAt()).isNotNull();
    Map<String, Object> breaches = sla.listBreaches(adminSupport, null, null, null);
    assertThat((Integer) breaches.get("breach_count")).isEqualTo(0);
  }

  @Test
  void ac008_slaAdherencePctUsesMultiply100() {
    assertThat(SlaAdherence.pct(8, 10)).isEqualTo(80.0);
    assertThat(SlaAdherence.pct(0, 0)).isEqualTo(0.0);
    tickets.seedResolvedWithinSla(true);
    tickets.seedResolvedWithinSla(false);
    Map<String, Object> breaches = sla.listBreaches(adminSupport, null, null, null);
    assertThat(((Number) breaches.get("sla_adherence_pct")).doubleValue()).isEqualTo(50.0);
  }

  @Test
  void ac009_nonSuperForbiddenOnPolicyUpdate() {
    SlaPolicy p = policies.listAll().getFirst();
    assertThatThrownBy(() -> sla.updatePolicy(adminOps, p.id(), 45, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void ac010_l4NotifiesSeniorOpsAllChannels() {
    Map<String, Object> created =
        ticketsSvc.create(
            customer,
            new TicketService.CreateCommand("OTHER", "Q", "d", "APP", null, null, null, "URGENT"));
    UUID id = (UUID) created.get("id");
    Ticket t = tickets.findById(id).orElseThrow();
    Instant due = NOW.minusSeconds(1500 * 60);
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
            due,
            due,
            due,
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
    assertThat(sla.processSlaBreaches(10)).isEqualTo(1);
    assertThat(tickets.findById(id).orElseThrow().slaL4NotifiedAt()).isEqualTo(NOW);
    assertThat(notifications.channelNotifies).isNotEmpty();
    assertThat(notifications.channelNotifies.getFirst()).contains("IN_APP", "WHATSAPP", "CALL");
  }

  @Test
  void listPoliciesAndNotFound() {
    Map<String, Object> listed = sla.listPolicies(adminSupport);
    assertThat(listed.get("sla_policies")).isInstanceOf(List.class);
    assertThatThrownBy(() -> sla.updatePolicy(adminSuper, UUID.randomUUID(), 1, 1))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SLA_POLICY_NOT_FOUND");
  }

  static final class FakeSlaPolicyStore implements SlaPolicyStore {
    private final Map<UUID, SlaPolicy> byId = new LinkedHashMap<>();

    FakeSlaPolicyStore() {
      seed("ALL", "LOW", 30, 1440, SlaLevel.L1);
      seed("ALL", "MEDIUM", 120, 2880, SlaLevel.L2);
      seed("ALL", "HIGH", 480, 4320, SlaLevel.L3);
      seed("ALL", "URGENT", 1440, 5760, SlaLevel.L4);
      seed("ORDER", "ANY", 30, 480, SlaLevel.L1);
      seed("PAYMENT", "HIGH", 120, 1440, SlaLevel.L2);
    }

    private void seed(String cat, String pri, int fr, int res, SlaLevel level) {
      UUID id = UUID.randomUUID();
      byId.put(id, new SlaPolicy(id, cat, pri, fr, res, level, null, NOW, NOW));
    }

    @Override
    public List<SlaPolicy> listAll() {
      return List.copyOf(byId.values());
    }

    @Override
    public Optional<SlaPolicy> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<SlaPolicy> resolve(TicketCategory category, TicketPriority priority) {
      Optional<SlaPolicy> specific =
          byId.values().stream()
              .filter(
                  p -> p.category().equals(category.name()) && p.priority().equals(priority.name()))
              .findFirst();
      if (specific.isPresent()) {
        return specific;
      }
      Optional<SlaPolicy> any =
          byId.values().stream()
              .filter(p -> p.category().equals(category.name()) && "ANY".equals(p.priority()))
              .findFirst();
      if (any.isPresent()) {
        return any;
      }
      return byId.values().stream()
          .filter(p -> "ALL".equals(p.category()) && p.priority().equals(priority.name()))
          .findFirst();
    }

    @Override
    public SlaPolicy update(
        UUID id,
        Integer firstResponseMinutes,
        Integer resolutionMinutes,
        SlaLevel slaLevel,
        UUID updatedBy,
        Instant updatedAt) {
      SlaPolicy e =
          findById(id)
              .orElseThrow(
                  () -> new AppException("SLA_POLICY_NOT_FOUND", "Policy ID does not exist", 404));
      SlaPolicy u =
          new SlaPolicy(
              e.id(),
              e.category(),
              e.priority(),
              firstResponseMinutes == null ? e.firstResponseSlaMinutes() : firstResponseMinutes,
              resolutionMinutes == null ? e.resolutionSlaMinutes() : resolutionMinutes,
              slaLevel == null ? e.slaLevel() : slaLevel,
              updatedBy,
              updatedAt,
              e.createdAt());
      byId.put(id, u);
      return u;
    }
  }

  static final class FakeEscalationMatrixStore implements EscalationMatrixStore {
    private final Map<SlaLevel, EscalationRule> byLevel = new LinkedHashMap<>();

    FakeEscalationMatrixStore() {
      put(SlaLevel.L1, "Default assignment", "Front-line Agents", List.of("IN_APP"), 30);
      put(
          SlaLevel.L2,
          "L1 first-response breach",
          "Senior Agents",
          List.of("IN_APP", "WHATSAPP"),
          120);
      put(SlaLevel.L3, "L2 breach", "Team Lead", List.of("IN_APP", "WHATSAPP"), 480);
      put(
          SlaLevel.L4,
          "L3 breach",
          "Senior Ops Manager",
          List.of("IN_APP", "WHATSAPP", "CALL"),
          1440);
    }

    private void put(
        SlaLevel level, String criteria, String team, List<String> channels, int minutes) {
      byLevel.put(
          level,
          new EscalationRule(
              UUID.randomUUID(), level, criteria, team, channels, minutes, null, NOW));
    }

    @Override
    public List<EscalationRule> listAll() {
      return List.copyOf(byLevel.values());
    }

    @Override
    public Optional<EscalationRule> findByLevel(SlaLevel level) {
      return Optional.ofNullable(byLevel.get(level));
    }

    @Override
    public List<SlaLevel> updateRules(List<RulePatch> patches, UUID updatedBy, Instant updatedAt) {
      List<SlaLevel> out = new ArrayList<>();
      for (RulePatch p : patches) {
        EscalationRule e = byLevel.get(p.level());
        if (e == null) {
          continue;
        }
        byLevel.put(
            p.level(),
            new EscalationRule(
                e.id(),
                e.level(),
                e.criteria(),
                e.assignedTeam(),
                p.notificationChannels() == null
                    ? e.notificationChannels()
                    : p.notificationChannels(),
                p.autoEscalateAfterMinutes() == null
                    ? e.autoEscalateAfterMinutes()
                    : p.autoEscalateAfterMinutes(),
                updatedBy,
                updatedAt));
        out.add(p.level());
      }
      return out;
    }
  }

  static final class RecordingAudit implements SupportAuditPort {
    final List<String> actions = new ArrayList<>();

    @Override
    public void append(
        String entityType,
        UUID actorId,
        String actorRole,
        UUID entityId,
        String action,
        Map<String, Object> before,
        Map<String, Object> after) {
      actions.add(action);
    }
  }
}
