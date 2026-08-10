package com.nammamedmate.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.SlaServiceTest.FakeSlaPolicyStore;
import com.nammamedmate.support.application.port.out.AgentStore;
import com.nammamedmate.support.application.port.out.CustomerLookupPort;
import com.nammamedmate.support.application.port.out.NotificationDispatchPort;
import com.nammamedmate.support.application.port.out.TicketStore;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketChannel;
import com.nammamedmate.support.domain.TicketIds;
import com.nammamedmate.support.domain.TicketMessage;
import com.nammamedmate.support.domain.TicketPriority;
import com.nammamedmate.support.domain.TicketStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TicketServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID AGENT = UUID.fromString("a0000002-0000-4000-8000-000000000002");

  private FakeTicketStore store;
  private FakeAgentStore agents;
  private FakeNotifications notifications;
  private FakeSlaPolicyStore policies;
  private TicketService service;

  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal admin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    store = new FakeTicketStore();
    agents = new FakeAgentStore();
    notifications = new FakeNotifications();
    policies = new FakeSlaPolicyStore();
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), true, 20, "Ravi Kumar", NOW));
    agents.put(new AgentProfile(ADMIN, List.of(), true, 20, "Admin Agent", NOW));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    AgentService agentService = new AgentService(agents, store, notifications, clock);
    service =
        new TicketService(
            store,
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
  void ac001_customerCreatesTicketWithCorrectIdFormat() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand(
                "ORDER", "Wrong items", "Got ibuprofen", "APP", null, null, List.of(), null));
    assertThat(created.get("ticket_id").toString()).matches("TKT-20260724-\\d{6}");
    TicketService.ListResult listed =
        service.listAdmin(admin, null, null, null, null, null, null, 1, 20, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tickets = (List<Map<String, Object>>) listed.data().get("tickets");
    assertThat(tickets).hasSize(1);
    assertThat(tickets.getFirst().get("ticket_id")).isEqualTo(created.get("ticket_id"));
  }

  @Test
  void ac002_orderUsesCategoryPolicyL1ThirtyMinutes() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand(
                "ORDER", "Wrong items", "desc", "APP", null, null, null, null));
    assertThat(created.get("priority")).isEqualTo("HIGH");
    assertThat(created.get("sla_level")).isEqualTo("L1");
    assertThat(created.get("sla_due_at")).isEqualTo(NOW.plusSeconds(30 * 60));
  }

  @Test
  void ac003_slaBreachEscalatesViaSlaService() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand(
                "ORDER", "Wrong items", "desc", "APP", null, null, null, "HIGH"));
    UUID id = (UUID) created.get("id");
    Ticket t = store.findById(id).orElseThrow();
    Instant due = NOW.minusSeconds(35 * 60);
    store.update(t.withSla(SlaLevel.L1, due, NOW));
    // also set first_response_due for breach detection
    store.update(
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
            SlaLevel.L1,
            due,
            due,
            t.resolutionDueAt(),
            t.assignedAgentId(),
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
    SlaService sla =
        new SlaService(
            policies,
            new SlaServiceTest.FakeEscalationMatrixStore(),
            store,
            agents,
            new FakeCustomers(),
            new com.nammamedmate.support.adapter.out.messaging.StubAutomationEscalate(
                store, notifications, Clock.fixed(NOW, ZoneOffset.UTC)),
            (entityType, actorId, actorRole, entityId, action, before, after) -> {},
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(sla.processSlaBreaches(10)).isEqualTo(1);
    assertThat(store.findById(id).orElseThrow().slaLevel()).isEqualTo(SlaLevel.L2);
  }

  @Test
  void ac004_internalNoteHiddenFromCustomer() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand(
                "ACCOUNT", "Help", "need help", "APP", null, null, null, "MEDIUM"));
    UUID id = (UUID) created.get("id");
    service.reply(
        admin, id, new TicketService.ReplyCommand("internal only", true, List.of(), null));
    Map<String, Object> customerView = service.get(customer, id);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> conv = (List<Map<String, Object>>) customerView.get("conversation");
    assertThat(conv).noneMatch(m -> Boolean.TRUE.equals(m.get("is_internal_note")));
    Map<String, Object> adminView = service.get(admin, id);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> adminConv = (List<Map<String, Object>>) adminView.get("conversation");
    assertThat(adminConv).anyMatch(m -> Boolean.TRUE.equals(m.get("is_internal_note")));
  }

  @Test
  void ac005_customerReplyOnResolvedReopens() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand(
                "PAYMENT", "Refund", "not received", "APP", null, null, null, null));
    UUID id = (UUID) created.get("id");
    service.resolve(admin, id, "Refund processed");
    service.reply(customer, id, new TicketService.ReplyCommand("still wrong", false, null, null));
    assertThat(store.findById(id).orElseThrow().status()).isEqualTo(TicketStatus.IN_PROGRESS);
  }

  @Test
  void ac006_roundRobinRespectsCapacityCap() {
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), true, 20, "Ravi", NOW));
    agents.put(new AgentProfile(ADMIN, List.of(), false, 20, "Admin Agent", NOW));
    for (int i = 0; i < 20; i++) {
      store.seedOpenAssigned(AGENT, TicketCategory.ORDER);
    }
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand("ORDER", "Cap", "desc", "APP", null, null, null, null));
    UUID id = (UUID) created.get("id");
    assertThat(store.findById(id).orElseThrow().assignedAgentId()).isNull();
    assertThatThrownBy(() -> service.assign(admin, id, AGENT))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("AGENT_AT_CAPACITY");
  }

  @Test
  void ac007_csatScheduledTwentyFourHoursAfterResolve() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand("OTHER", "Q", "d", "APP", null, null, null, null));
    UUID id = (UUID) created.get("id");
    Map<String, Object> resolved = service.resolve(admin, id, "Done");
    assertThat(resolved.get("csat_survey_scheduled_at")).isEqualTo(NOW.plusSeconds(24 * 3600));
  }

  @Test
  void ac008_adminCreateOnBehalfAttributesCustomer() {
    Map<String, Object> created =
        service.createOnBehalf(
            admin,
            new TicketService.AdminCreateCommand(
                CUST, "PAYMENT", "Refund delay", "Called in", null, "PHONE"));
    assertThat(created.get("created_for_customer_id")).isEqualTo(CUST);
    assertThat(created.get("created_by_admin_id")).isEqualTo(ADMIN);
    Ticket t = store.byHumanId(created.get("ticket_id").toString());
    assertThat(t.customerId()).isEqualTo(CUST);
    assertThat(t.createdByAdminId()).isEqualTo(ADMIN);
  }

  @Test
  void ac009_urgentPrioritySetsL4() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand("OTHER", "Q", "d", "APP", null, null, null, "LOW"));
    UUID id = (UUID) created.get("id");
    Map<String, Object> updated = service.changePriority(admin, id, "URGENT");
    assertThat(updated.get("priority")).isEqualTo("URGENT");
    assertThat(updated.get("sla_level")).isEqualTo("L4");
  }

  @Test
  void ac010_csvExportContainsVisibleColumns() {
    service.create(
        customer,
        new TicketService.CreateCommand(
            "ORDER", "Wrong items", "desc", "APP", null, null, null, null));
    TicketService.ListResult exported =
        service.listAdmin(admin, null, null, null, null, null, null, 1, 20, true);
    String csv = exported.data().get("csv").toString();
    assertThat(csv).contains("ticket_id,customer_id,category,subject,status,priority");
    assertThat(csv).contains("TKT-20260724-");
    assertThat(service.exportCsvBytes(exported)).isNotEmpty();
  }

  @Test
  void assignAgentNotFound() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand("OTHER", "Q", "d", "APP", null, null, null, null));
    UUID id = (UUID) created.get("id");
    assertThatThrownBy(() -> service.assign(admin, id, UUID.randomUUID()))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("AGENT_NOT_FOUND");
  }

  @Test
  void escalateAndReopenAndCsatDispatch() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand(
                "OTHER", "Q", "d", "EMAIL", null, null, null, "MEDIUM"));
    UUID id = (UUID) created.get("id");
    Map<String, Object> esc = service.escalate(admin, id, "L3", "waiting");
    assertThat(esc.get("sla_level")).isEqualTo("L3");
    assertThat(esc.get("supervisor_notified")).isEqualTo(true);
    service.resolve(admin, id, "fixed");
    service.reopen(customer, id, "still broken");
    assertThat(store.findById(id).orElseThrow().status()).isEqualTo(TicketStatus.IN_PROGRESS);
    service.resolve(admin, id, "fixed again");
    Ticket resolved = store.findById(id).orElseThrow();
    store.update(
        resolved.withResolved(NOW.minusSeconds(25 * 3600), "x", NOW.minusSeconds(3600), NOW));
    assertThat(service.dispatchDueCsatSurveys(10)).isEqualTo(1);
    assertThat(notifications.csat).hasSize(1);
  }

  @Test
  void agentReplySetsFirstResponseAndAwaitingCustomer() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand("OTHER", "Q", "d", "APP", null, null, null, null));
    UUID id = (UUID) created.get("id");
    service.reply(admin, id, new TicketService.ReplyCommand("looking", false, List.of(), null));
    Ticket t = store.findById(id).orElseThrow();
    assertThat(t.firstResponseAt()).isEqualTo(NOW);
    assertThat(t.status()).isEqualTo(TicketStatus.AWAITING_CUSTOMER);
  }

  @Test
  void validationAndForbiddenPaths() {
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new TicketService.CreateCommand(null, "s", "d", "APP", null, null, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.get(customer, UUID.randomUUID()))
        .isInstanceOf(AppException.class);
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand("OTHER", "Q", "d", "APP", null, null, null, null));
    UUID other = UUID.fromString("c0000001-0000-4000-8000-000000000099");
    MedmatePrincipal stranger =
        new MedmatePrincipal(other, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.get(stranger, (UUID) created.get("id")))
        .isInstanceOf(AppException.class);
  }

  static final class FakeTicketStore implements TicketStore {
    private final Map<UUID, Ticket> byId = new HashMap<>();
    private final List<TicketMessage> messages = new ArrayList<>();
    private final Map<LocalDate, AtomicInteger> seq = new HashMap<>();

    @Override
    public int nextTicketSeq(LocalDate day) {
      return seq.computeIfAbsent(day, d -> new AtomicInteger(0)).incrementAndGet();
    }

    @Override
    public Ticket insert(Ticket ticket) {
      byId.put(ticket.id(), ticket);
      return ticket;
    }

    @Override
    public void update(Ticket ticket) {
      byId.put(ticket.id(), ticket);
    }

    @Override
    public Optional<Ticket> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Ticket> findByTicketId(String ticketId) {
      return byId.values().stream().filter(t -> t.ticketId().equals(ticketId)).findFirst();
    }

    Ticket byHumanId(String ticketId) {
      return findByTicketId(ticketId).orElseThrow();
    }

    void seedOpenAssigned(UUID agentId, TicketCategory category) {
      Instant now = NOW;
      LocalDate day = TicketIds.dayKey(now);
      int n = nextTicketSeq(day);
      Instant due = now.plusSeconds(7200);
      Ticket t =
          new Ticket(
              UUID.randomUUID(),
              TicketIds.format(day, n),
              CUST,
              null,
              null,
              category,
              "seed",
              TicketStatus.OPEN,
              TicketPriority.MEDIUM,
              SlaLevel.L2,
              due,
              due,
              due.plusSeconds(3600),
              agentId,
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
      insert(t);
    }

    void seedResolvedWithinSla(boolean within) {
      Instant now = NOW;
      LocalDate day = TicketIds.dayKey(now);
      int n = nextTicketSeq(day);
      Instant due = now.plusSeconds(3600);
      Instant resolved = within ? now.plusSeconds(60) : now.plusSeconds(7200);
      Ticket t =
          new Ticket(
              UUID.randomUUID(),
              TicketIds.format(day, n),
              CUST,
              null,
              null,
              TicketCategory.OTHER,
              "resolved",
              TicketStatus.RESOLVED,
              TicketPriority.MEDIUM,
              SlaLevel.L2,
              due,
              due,
              due,
              null,
              TicketChannel.APP,
              now.plusSeconds(30),
              resolved,
              "done",
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
      insert(t);
    }

    @Override
    public List<Ticket> list(ListFilter filter) {
      return byId.values().stream()
          .filter(t -> match(t, filter))
          .sorted(Comparator.comparing(Ticket::createdAt).reversed())
          .skip(filter.offset())
          .limit(filter.limit())
          .collect(Collectors.toList());
    }

    @Override
    public long count(ListFilter filter) {
      return byId.values().stream().filter(t -> match(t, filter)).count();
    }

    private boolean match(Ticket t, ListFilter filter) {
      if (filter.status() != null && t.status() != filter.status()) {
        return false;
      }
      if (filter.priority() != null && t.priority() != filter.priority()) {
        return false;
      }
      if (filter.category() != null && t.category() != filter.category()) {
        return false;
      }
      if (filter.channel() != null && t.channel() != filter.channel()) {
        return false;
      }
      if (filter.assignedAgentId() != null
          && !filter.assignedAgentId().equals(t.assignedAgentId())) {
        return false;
      }
      if (filter.q() != null
          && !(t.ticketId().contains(filter.q()) || t.subject().contains(filter.q()))) {
        return false;
      }
      return true;
    }

    @Override
    public Chips chips(Instant now) {
      long open = byId.values().stream().filter(t -> t.status() == TicketStatus.OPEN).count();
      long ip =
          byId.values().stream()
              .filter(
                  t ->
                      t.status() == TicketStatus.IN_PROGRESS
                          || t.status() == TicketStatus.AWAITING_CUSTOMER)
              .count();
      long breached = byId.values().stream().filter(t -> t.slaBreached(now)).count();
      return new Chips(open, ip, breached, 0, 0, 0.0);
    }

    @Override
    public TicketMessage insertMessage(TicketMessage message) {
      messages.add(message);
      return message;
    }

    @Override
    public List<TicketMessage> listMessages(UUID ticketId) {
      return messages.stream().filter(m -> m.ticketId().equals(ticketId)).toList();
    }

    @Override
    public int countOpenAssigned(UUID agentId) {
      return (int)
          byId.values().stream()
              .filter(
                  t ->
                      agentId.equals(t.assignedAgentId())
                          && (t.status() == TicketStatus.OPEN
                              || t.status() == TicketStatus.IN_PROGRESS
                              || t.status() == TicketStatus.AWAITING_CUSTOMER))
              .count();
    }

    @Override
    public int countUnassignedOpen() {
      return (int)
          byId.values().stream()
              .filter(
                  t ->
                      t.assignedAgentId() == null
                          && (t.status() == TicketStatus.OPEN
                              || t.status() == TicketStatus.IN_PROGRESS))
              .count();
    }

    @Override
    public List<Ticket> listAssignedOpen(UUID agentId) {
      return byId.values().stream()
          .filter(
              t ->
                  agentId.equals(t.assignedAgentId())
                      && (t.status() == TicketStatus.OPEN
                          || t.status() == TicketStatus.IN_PROGRESS
                          || t.status() == TicketStatus.AWAITING_CUSTOMER))
          .sorted(Comparator.comparing(Ticket::createdAt))
          .toList();
    }

    @Override
    public List<Ticket> listResolvedByAgent(
        UUID agentId, Instant fromInclusive, Instant toExclusive) {
      return byId.values().stream()
          .filter(
              t ->
                  agentId.equals(t.assignedAgentId())
                      && (t.status() == TicketStatus.RESOLVED || t.status() == TicketStatus.CLOSED)
                      && t.resolvedAt() != null
                      && !t.resolvedAt().isBefore(fromInclusive)
                      && t.resolvedAt().isBefore(toExclusive))
          .sorted(Comparator.comparing(Ticket::resolvedAt))
          .toList();
    }

    @Override
    public List<Ticket> findDueCsatSurveys(Instant now, int limit) {
      return byId.values().stream()
          .filter(
              t ->
                  t.status() == TicketStatus.RESOLVED
                      && t.csatSurveyScheduledAt() != null
                      && !t.csatSurveyScheduledAt().isAfter(now)
                      && t.csatSurveySentAt() == null)
          .limit(limit)
          .toList();
    }

    @Override
    public List<Ticket> findSlaBreachedWithoutFirstResponse(Instant now, int limit) {
      return byId.values().stream().filter(t -> t.slaBreached(now)).limit(limit).toList();
    }

    @Override
    public List<Ticket> findOpenForSlaScan(int limit) {
      return byId.values().stream()
          .filter(
              t ->
                  t.status() != TicketStatus.AWAITING_CUSTOMER
                      && t.status() != TicketStatus.RESOLVED
                      && t.status() != TicketStatus.CLOSED
                      && t.slaPausedAt() == null)
          .limit(limit)
          .toList();
    }

    @Override
    public ResolvedSlaStats resolvedSlaStats() {
      long total =
          byId.values().stream()
              .filter(
                  t ->
                      (t.status() == TicketStatus.RESOLVED || t.status() == TicketStatus.CLOSED)
                          && t.resolvedAt() != null)
              .count();
      long within =
          byId.values().stream()
              .filter(
                  t ->
                      (t.status() == TicketStatus.RESOLVED || t.status() == TicketStatus.CLOSED)
                          && t.resolvedAt() != null
                          && !t.resolvedAt().isAfter(t.resolutionDueAt())
                          && (t.firstResponseAt() == null
                              || !t.firstResponseAt().isAfter(t.firstResponseDueAt())))
              .count();
      return new ResolvedSlaStats(within, total);
    }
  }

  static final class FakeAgentStore implements AgentStore {
    private final Map<UUID, AgentProfile> byId = new HashMap<>();
    private final Map<String, com.nammamedmate.support.domain.AgentPerformanceSnapshot> snaps =
        new HashMap<>();
    private final Map<UUID, String> emails = new HashMap<>();

    void put(AgentProfile p) {
      byId.put(p.adminUserId(), p);
    }

    void putEmail(UUID id, String email) {
      emails.put(id, email);
    }

    @Override
    public Optional<AgentProfile> findById(UUID adminUserId) {
      return Optional.ofNullable(byId.get(adminUserId));
    }

    @Override
    public List<AgentProfile> listAll() {
      return List.copyOf(byId.values());
    }

    @Override
    public List<AgentProfile> listOnline() {
      return byId.values().stream().filter(AgentProfile::online).toList();
    }

    @Override
    public List<AgentProfile> listOnlineForCategory(TicketCategory category) {
      return byId.values().stream()
          .filter(AgentProfile::online)
          .filter(a -> a.matchesSpecialty(category))
          .toList();
    }

    @Override
    public AgentProfile updateOnline(UUID adminUserId, boolean online, Instant updatedAt) {
      AgentProfile existing = byId.get(adminUserId);
      if (existing == null) {
        throw new AppException("AGENT_NOT_FOUND", "Agent ID does not exist", 404);
      }
      AgentProfile updated =
          new AgentProfile(
              existing.adminUserId(),
              existing.specialties(),
              online,
              existing.maxLoad(),
              existing.displayName(),
              updatedAt);
      byId.put(adminUserId, updated);
      return updated;
    }

    @Override
    public Optional<String> findEmail(UUID adminUserId) {
      return Optional.ofNullable(emails.get(adminUserId));
    }

    @Override
    public void upsertSnapshot(com.nammamedmate.support.domain.AgentPerformanceSnapshot snapshot) {
      snaps.put(snapshot.agentId() + "|" + snapshot.weekStart(), snapshot);
    }

    @Override
    public List<com.nammamedmate.support.domain.AgentPerformanceSnapshot> listSnapshots(
        UUID agentId) {
      return snaps.values().stream()
          .filter(s -> s.agentId().equals(agentId))
          .sorted(
              Comparator.comparing(
                  com.nammamedmate.support.domain.AgentPerformanceSnapshot::weekStart))
          .toList();
    }

    @Override
    public Optional<com.nammamedmate.support.domain.AgentPerformanceSnapshot> findSnapshot(
        UUID agentId, LocalDate weekStart) {
      return Optional.ofNullable(snaps.get(agentId + "|" + weekStart));
    }
  }

  static final class FakeCustomers implements CustomerLookupPort {
    @Override
    public Optional<CustomerContext> find(UUID customerId) {
      return Optional.of(new CustomerContext(customerId, "Priya Sharma", 24, 8400));
    }

    @Override
    public Optional<String> displayName(UUID customerId) {
      return Optional.of("Priya Sharma");
    }
  }

  static final class FakeNotifications implements NotificationDispatchPort {
    final List<UUID> csat = new ArrayList<>();
    final List<List<String>> channelNotifies = new ArrayList<>();

    @Override
    public void notifyEscalation(UUID ticketId, UUID customerId, String slaLevel) {}

    @Override
    public void notifyCsatSurvey(UUID ticketId, UUID customerId, String channel) {
      csat.add(ticketId);
    }

    @Override
    public void notifySupervisorEscalation(UUID ticketId, String reason) {}

    @Override
    public void notifyEscalationChannels(
        UUID ticketId, UUID customerId, String slaLevel, String team, List<String> channels) {
      channelNotifies.add(List.copyOf(channels));
    }
  }
}
