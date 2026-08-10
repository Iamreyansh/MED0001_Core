package com.nammamedmate.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.SlaServiceTest.FakeSlaPolicyStore;
import com.nammamedmate.support.application.TicketServiceTest.FakeAgentStore;
import com.nammamedmate.support.application.TicketServiceTest.FakeCustomers;
import com.nammamedmate.support.application.TicketServiceTest.FakeNotifications;
import com.nammamedmate.support.application.TicketServiceTest.FakeTicketStore;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.CannedResponse;
import com.nammamedmate.support.domain.SenderType;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupportCoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID AGENT = UUID.fromString("a0000002-0000-4000-8000-000000000002");
  private static final UUID PHARM = UUID.fromString("b0000001-0000-4000-8000-000000000001");

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
    agents.put(new AgentProfile(ADMIN, List.of(), true, 20, "Admin", NOW));
    agents.put(new AgentProfile(AGENT, List.of("order"), true, 20, "Ravi", NOW));
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
  void pharmacyCreateAndViewAndFilters() {
    MedmatePrincipal pharmacy =
        new MedmatePrincipal(PHARM, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");
    Map<String, Object> created =
        service.create(
            pharmacy,
            new TicketService.CreateCommand(
                "PHARMACY",
                "Stock, \"quoted\"",
                "out of stock sync",
                "APP",
                null,
                PHARM,
                List.of("a"),
                "LOW"));
    UUID id = (UUID) created.get("id");
    assertThat(created.get("priority")).isEqualTo("LOW");
    assertThat(service.get(pharmacy, id).get("status")).isNotNull();
    service.reply(pharmacy, id, new TicketService.ReplyCommand("update", false, List.of(), null));
    MedmatePrincipal staff =
        new MedmatePrincipal(PHARM, AuthRole.PHARMACY_STAFF, PHARM, TokenScope.FULL, "j");
    assertThat(service.get(staff, id).get("id")).isEqualTo(id);
    assertThat(
            service
                .listAdmin(admin, null, "LOW", "PHARMACY", "APP", "Stock", null, 1, 10, true)
                .data()
                .get("csv")
                .toString())
        .contains("Stock");
  }

  @Test
  void nullCreateCommandAndLongSubject() {
    assertThatThrownBy(() -> service.create(customer, null)).isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new TicketService.CreateCommand(
                        "OTHER", "x".repeat(201), "d", "APP", null, null, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.assign(admin, UUID.randomUUID(), null))
        .isInstanceOf(AppException.class);
  }

  @Test
  void replyBranchesAwaitingReopenAndSecondAgentReply() {
    KnowledgeBaseServiceTest.FakeCanned canned = new KnowledgeBaseServiceTest.FakeCanned();
    CannedResponse seeded =
        new CannedResponse(
            UUID.randomUUID(),
            "t",
            TicketCategory.OTHER,
            "canned body",
            "/seed",
            0,
            null,
            ADMIN,
            null,
            NOW,
            NOW);
    canned.insert(seeded);
    service =
        new TicketService(
            store,
            agents,
            new FakeCustomers(),
            notifications,
            policies,
            canned,
            new KbTestDoubles.EmptyOrders(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new AgentService(agents, store, notifications, Clock.fixed(NOW, ZoneOffset.UTC)));
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand(
                "OTHER", "s", "d", "APP", UUID.randomUUID(), null, null, "MEDIUM"));
    UUID id = (UUID) created.get("id");
    service.reply(
        admin, id, new TicketService.ReplyCommand("first", false, List.of(), seeded.id()));
    assertThat(store.findById(id).orElseThrow().status()).isEqualTo(TicketStatus.AWAITING_CUSTOMER);
    // admin follow-up while already AWAITING → ternary else branch (keep status)
    service.reply(admin, id, new TicketService.ReplyCommand("follow-up", false, null, null));
    assertThat(store.findById(id).orElseThrow().status()).isEqualTo(TicketStatus.AWAITING_CUSTOMER);
    service.reply(customer, id, new TicketService.ReplyCommand("thanks", false, null, null));
    assertThat(store.findById(id).orElseThrow().status()).isEqualTo(TicketStatus.IN_PROGRESS);
    service.reply(admin, id, new TicketService.ReplyCommand("second", false, null, null));
    Map<String, Object> detail = service.get(admin, id);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> conv = (List<Map<String, Object>>) detail.get("conversation");
    assertThat(conv.stream().anyMatch(m -> m.containsKey("canned_response_id"))).isTrue();
  }

  @Test
  void assignSameAgentAtCapAllowedAndEscalateDowngradeIgnored() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand("OTHER", "s", "d", "APP", null, null, null, null));
    UUID id = (UUID) created.get("id");
    service.assign(admin, id, AGENT);
    for (int i = 0; i < 19; i++) {
      store.seedOpenAssigned(AGENT, TicketCategory.OTHER);
    }
    // AGENT already assigned on this ticket + 19 others = 20; re-assign same agent OK
    assertThat(service.assign(admin, id, AGENT).get("assigned_to")).isEqualTo(AGENT);
    service.escalate(admin, id, "L4", "up");
    Map<String, Object> down = service.escalate(admin, id, "L1", "noop");
    assertThat(down.get("sla_level")).isEqualTo("L4");
  }

  @Test
  void priorityAfterFirstResponseAndSlaL4Skip() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand("OTHER", "s", "d", "APP", null, null, null, "LOW"));
    UUID id = (UUID) created.get("id");
    service.reply(admin, id, new TicketService.ReplyCommand("hi", false, null, null));
    assertThat(service.changePriority(admin, id, "HIGH").get("sla_level")).isEqualTo("L3");
    Ticket t = store.findById(id).orElseThrow();
    store.update(
        t.withSla(SlaLevel.L4, NOW.minusSeconds(10), NOW)
            .withFirstResponse(null, TicketStatus.OPEN, NOW));
    // restore first_response null for breach scan
    Ticket breached =
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
            NOW.minusSeconds(1),
            NOW.minusSeconds(1),
            NOW.minusSeconds(1),
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
            NOW,
            NOW);
    store.update(breached);
    SlaService slaSvc =
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
    // L4 under auto_escalate_after_minutes → no action yet
    assertThat(slaSvc.processSlaBreaches(10)).isEqualTo(0);
  }

  @Test
  void reopenAdminAndClosedCustomerReplyAndUnauth() {
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand("OTHER", "s", "d", "EMAIL", null, null, null, null));
    UUID id = (UUID) created.get("id");
    service.resolve(admin, id, "done");
    assertThat(service.reopen(admin, id, " ").get("status")).isEqualTo("IN_PROGRESS");
    service.resolve(admin, id, "done2");
    assertThat(service.reopen(customer, id, "customer reason").get("status"))
        .isEqualTo("IN_PROGRESS");
    service.resolve(admin, id, "done3");
    Ticket closed = store.findById(id).orElseThrow().withStatus(TicketStatus.CLOSED, NOW);
    store.update(closed);
    assertThat(service.reopen(admin, id, null).get("status")).isEqualTo("IN_PROGRESS");
    service.resolve(admin, id, "done4");
    Ticket closed2 = store.findById(id).orElseThrow().withStatus(TicketStatus.CLOSED, NOW);
    store.update(closed2);
    service.reply(
        customer, id, new TicketService.ReplyCommand("reopen via reply", false, null, null));
    assertThat(store.findById(id).orElseThrow().status()).isEqualTo(TicketStatus.IN_PROGRESS);
    assertThatThrownBy(
            () -> service.listAdmin(null, null, null, null, null, null, null, 1, 20, false))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.resolve(admin, id, null)).isInstanceOf(AppException.class);
  }

  @Test
  void invalidInputsAndAdminCreatePath() {
    MedmatePrincipal rider =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.RIDER, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new TicketService.CreateCommand(
                        "NOPE", "s", "d", "APP", null, null, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new TicketService.CreateCommand(
                        "OTHER", "s", "d", "FAX", null, null, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new TicketService.CreateCommand(
                        "OTHER", "s", "d", "APP", null, null, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.createOnBehalf(admin, null)).isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.createOnBehalf(
                    admin,
                    new TicketService.AdminCreateCommand(null, "OTHER", "s", "d", null, "APP")))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.reply(
                    admin,
                    UUID.randomUUID(),
                    new TicketService.ReplyCommand(null, false, null, null)))
        .isInstanceOf(AppException.class);
    // force both changePriority due branches
    Map<String, Object> fresh =
        service.create(
            customer,
            new TicketService.CreateCommand("OTHER", "fresh", "d", "APP", null, null, null, "LOW"));
    UUID freshId = (UUID) fresh.get("id");
    assertThatThrownBy(
            () ->
                service.reply(
                    admin, freshId, new TicketService.ReplyCommand(null, false, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.reply(
                    admin, freshId, new TicketService.ReplyCommand(" ", false, null, null)))
        .isInstanceOf(AppException.class);
    assertThat(service.changePriority(admin, freshId, "MEDIUM").get("sla_level")).isEqualTo("L2");
    // pharmacy staff view by customer_id match; pharmacyId-null forbid
    MedmatePrincipal staffViewer =
        new MedmatePrincipal(PHARM, AuthRole.PHARMACY_STAFF, PHARM, TokenScope.FULL, "j");
    Ticket noPharmacy =
        new Ticket(
            UUID.randomUUID(),
            TicketIds.format(LocalDate.of(2026, 7, 24), 901),
            UUID.randomUUID(),
            null,
            null,
            TicketCategory.OTHER,
            "x",
            TicketStatus.OPEN,
            TicketPriority.MEDIUM,
            SlaLevel.L2,
            NOW.plusSeconds(10),
            NOW.plusSeconds(10),
            NOW.plusSeconds(10),
            AGENT,
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
            NOW,
            NOW);
    store.insert(noPharmacy);
    assertThatThrownBy(() -> service.get(staffViewer, noPharmacy.id()))
        .isInstanceOf(AppException.class);
    assertThat(
            service
                .listAdmin(admin, null, null, null, null, null, null, 1, 100, true)
                .data()
                .get("csv")
                .toString())
        .contains(AGENT.toString());
    assertThatThrownBy(() -> service.escalate(admin, UUID.randomUUID(), "LX", "r"))
        .isInstanceOf(AppException.class);
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand(
                "OTHER", "s", "d", "WHATSAPP", null, null, null, "MEDIUM"));
    UUID id = (UUID) created.get("id");
    assertThatThrownBy(() -> service.resolve(admin, id, " ")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.reopen(admin, id, "x")).isInstanceOf(AppException.class);
    assertThatThrownBy(
            () -> service.reply(admin, id, new TicketService.ReplyCommand(" ", false, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.create(
                    rider,
                    new TicketService.CreateCommand(
                        "OTHER", "s", "d", "APP", null, null, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () -> service.listAdmin(admin, "BAD", null, null, null, null, null, 1, 20, false))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.changePriority(admin, id, "NOPE"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.changePriority(admin, id, null))
        .isInstanceOf(AppException.class);
    assertThat(
            service
                .listAdmin(admin, "OPEN", "MEDIUM", null, null, null, null, 1, 20, false)
                .meta()
                .page())
        .isEqualTo(1);
    assertThat(
            service
                .listAdmin(admin, null, null, null, null, null, null, 1, 20, true)
                .data()
                .get("csv")
                .toString())
        .contains("ticket_id");
  }

  @Test
  void moreBranchGaps() {
    agents.put(new AgentProfile(ADMIN, List.of(), false, 20, "Admin", NOW));
    agents.put(new AgentProfile(AGENT, List.of("PAYMENT"), true, 20, "Ravi", NOW));
    // stays OPEN — AGENT specialty PAYMENT won't match OTHER
    Map<String, Object> created =
        service.create(
            customer,
            new TicketService.CreateCommand("OTHER", "plain", "d", null, null, null, null, " "));
    UUID id = (UUID) created.get("id");
    assertThat(store.findById(id).orElseThrow().status()).isEqualTo(TicketStatus.OPEN);
    service.reply(customer, id, new TicketService.ReplyCommand("bump", false, List.of(), null));
    assertThat(store.findById(id).orElseThrow().status()).isEqualTo(TicketStatus.IN_PROGRESS);
    // first_response already set but still OPEN/IN_PROGRESS → withStatus path
    Ticket openAgain = store.findById(id).orElseThrow();
    store.update(openAgain.withFirstResponse(NOW, TicketStatus.IN_PROGRESS, NOW));
    service.reply(admin, id, new TicketService.ReplyCommand("again", false, null, null));
    assertThat(store.findById(id).orElseThrow().status()).isEqualTo(TicketStatus.AWAITING_CUSTOMER);

    // assigned agent missing from profile store → null name
    service.assign(admin, id, ADMIN);
    agents.put(new AgentProfile(ADMIN, List.of(), false, 20, "gone", NOW));
    // remove AGENT visibility for name lookup by using empty optional: overwrite with missing via
    // new store without ADMIN
    FakeAgentStore emptyAgents = new FakeAgentStore();
    Clock clock2 = Clock.fixed(NOW, ZoneOffset.UTC);
    TicketService svc2 =
        new TicketService(
            store,
            emptyAgents,
            new FakeCustomers(),
            notifications,
            policies,
            new com.nammamedmate.support.application.KbTestDoubles.EmptyCanned(),
            new com.nammamedmate.support.application.KbTestDoubles.EmptyOrders(),
            clock2,
            new AgentService(emptyAgents, store, notifications, clock2));
    assertThat(svc2.get(admin, id).get("assigned_agent_name")).isNull();

    service.escalate(admin, id, "  ", null);
    service.escalate(admin, id, "", "reason");

    // past due priority change without first response
    Ticket t = store.findById(id).orElseThrow();
    store.update(
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
            NOW.minusSeconds(3600),
            NOW.minusSeconds(3600),
            NOW.minusSeconds(3600),
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
            NOW.minusSeconds(10_000),
            NOW));
    assertThat(service.changePriority(admin, id, "MEDIUM").get("sla_level")).isEqualTo("L2");

    assertThat(
            service.exportCsvBytes(
                new TicketService.ListResult(Map.of("tickets", List.of()), null)))
        .isEmpty();
    assertThatThrownBy(() -> service.assign(customer, id, AGENT)).isInstanceOf(AppException.class);
    // capacity: different agent while at cap
    UUID otherAgent = UUID.fromString("a0000003-0000-4000-8000-000000000003");
    agents.put(new AgentProfile(otherAgent, List.of(), true, 1, "Other", NOW));
    store.seedOpenAssigned(otherAgent, TicketCategory.OTHER);
    // at-capacity with unassigned ticket
    agents.put(new AgentProfile(otherAgent, List.of(), true, 1, "Other", NOW));
    Map<String, Object> overflow =
        service.create(
            customer,
            new TicketService.CreateCommand(
                "OTHER", "overflow", "d", "APP", null, null, null, null));
    UUID overflowId = (UUID) overflow.get("id");
    assertThat(store.findById(overflowId).orElseThrow().assignedAgentId()).isNull();
    assertThatThrownBy(() -> service.assign(admin, overflowId, otherAgent))
        .isInstanceOf(AppException.class);
    // at-capacity when ticket already assigned to someone else
    service.assign(admin, overflowId, ADMIN);
    agents.put(new AgentProfile(ADMIN, List.of(), false, 20, "Admin", NOW));
    assertThatThrownBy(() -> service.assign(admin, overflowId, otherAgent))
        .isInstanceOf(AppException.class);
    // unassigned ticket in CSV (null agent col)
    agents.put(new AgentProfile(ADMIN, List.of(), false, 20, "Admin", NOW));
    agents.put(new AgentProfile(AGENT, List.of("PAYMENT"), false, 20, "Ravi", NOW));
    Map<String, Object> unassigned =
        service.create(
            customer,
            new TicketService.CreateCommand(
                "OTHER", "unassigned-csv", "d", "APP", null, null, null, null));
    assertThat(store.findById((UUID) unassigned.get("id")).orElseThrow().assignedAgentId())
        .isNull();
    assertThat(
            service
                .listAdmin(admin, null, null, null, null, "unassigned-csv", null, 1, 20, true)
                .data()
                .get("csv")
                .toString())
        .contains("unassigned-csv");
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new TicketService.CreateCommand(
                        "OTHER", " ", "d", "APP", null, null, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new TicketService.CreateCommand(
                        "OTHER", "s", " ", "APP", null, null, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.reply(admin, id, null)).isInstanceOf(AppException.class);
    assertThatCode(() -> service.listAdmin(admin, " ", null, null, null, " ", null, 1, 20, false))
        .doesNotThrowAnyException();
    // pharmacy views by pharmacy_id match (different customer_id)
    UUID otherCust = UUID.fromString("c0000001-0000-4000-8000-000000000099");
    Ticket pharmTicket =
        new Ticket(
            UUID.randomUUID(),
            TicketIds.format(LocalDate.of(2026, 7, 24), 900),
            otherCust,
            PHARM,
            null,
            TicketCategory.PHARMACY,
            "p",
            TicketStatus.OPEN,
            TicketPriority.MEDIUM,
            SlaLevel.L2,
            NOW.plusSeconds(100),
            NOW.plusSeconds(100),
            NOW.plusSeconds(100),
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
            NOW,
            NOW);
    store.insert(pharmTicket);
    MedmatePrincipal pharmacy =
        new MedmatePrincipal(PHARM, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");
    assertThat(service.get(pharmacy, pharmTicket.id()).get("id")).isEqualTo(pharmTicket.id());
    assertThatThrownBy(
            () ->
                service.get(
                    new MedmatePrincipal(
                        UUID.randomUUID(),
                        AuthRole.PHARMACY_OWNER,
                        UUID.randomUUID(),
                        TokenScope.FULL,
                        "j"),
                    pharmTicket.id()))
        .isInstanceOf(AppException.class);

    Ticket open =
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
            NOW.plusSeconds(10),
            NOW.plusSeconds(10),
            NOW.plusSeconds(10),
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
            NOW,
            NOW);
    assertThat(open.withAssignment(AGENT, NOW).status()).isEqualTo(TicketStatus.IN_PROGRESS);
    assertThat(open.withStatus(TicketStatus.IN_PROGRESS, NOW).withAssignment(AGENT, NOW).status())
        .isEqualTo(TicketStatus.IN_PROGRESS);
    assertThat(open.withStatus(TicketStatus.RESOLVED, NOW).slaBreached(NOW.plusSeconds(100)))
        .isFalse();
    assertThat(open.withStatus(TicketStatus.CLOSED, NOW).slaBreached(NOW.plusSeconds(100)))
        .isFalse();
    assertThat(open.slaBreached(NOW)).isFalse();
    assertThat(
            open.withFirstResponse(NOW, TicketStatus.OPEN, NOW).slaBreached(NOW.plusSeconds(999)))
        .isFalse();
    assertThat(
            new TicketMessage(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    SenderType.SYSTEM,
                    CUST,
                    "s",
                    "m",
                    false,
                    null,
                    null,
                    NOW)
                .attachments())
        .isEmpty();
    assertThat(
            new TicketMessage(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    SenderType.SYSTEM,
                    CUST,
                    "s",
                    "m",
                    false,
                    null,
                    java.util.Arrays.asList("a", null),
                    NOW)
                .attachments())
        .containsExactly("a");
    assertThat(new AgentProfile(AGENT, null, true, 0, "X", NOW).specialties()).isEmpty();
    assertThat(
            new AgentProfile(AGENT, java.util.Arrays.asList("ORDER", null), true, 5, "X", NOW)
                .matchesSpecialty(TicketCategory.PAYMENT))
        .isFalse();

    // remaining TicketService validation / auth branches
    assertThatThrownBy(() -> service.create(null, createdCmd())).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.resolve(admin, id, null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.reopen(customer, pharmTicket.id(), "x"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.changePriority(admin, id, null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.escalate(admin, id, "NOT_A_LEVEL", "r"))
        .isInstanceOf(AppException.class);
    MedmatePrincipal staff =
        new MedmatePrincipal(PHARM, AuthRole.PHARMACY_STAFF, PHARM, TokenScope.FULL, "j");
    assertThat(
            service
                .create(
                    staff,
                    new TicketService.CreateCommand(
                        "OTHER", "staff", "d", "APP", null, null, null, null))
                .get("status"))
        .isNotNull();
    service.escalate(admin, id, null, "n");
  }

  private static TicketService.CreateCommand createdCmd() {
    return new TicketService.CreateCommand("OTHER", "s", "d", "APP", null, null, null, null);
  }
}
