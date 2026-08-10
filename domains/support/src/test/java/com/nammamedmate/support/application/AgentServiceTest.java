package com.nammamedmate.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.TicketServiceTest.FakeAgentStore;
import com.nammamedmate.support.application.TicketServiceTest.FakeNotifications;
import com.nammamedmate.support.application.TicketServiceTest.FakeTicketStore;
import com.nammamedmate.support.application.port.out.TicketStore;
import com.nammamedmate.support.domain.AgentPerformanceSnapshot;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketChannel;
import com.nammamedmate.support.domain.TicketPriority;
import com.nammamedmate.support.domain.TicketStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z"); // Fri IST afternoon
  private static final UUID SUPER = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID OPS = UUID.fromString("a0000001-0000-4000-8000-000000000010");
  private static final UUID AGENT = UUID.fromString("a0000002-0000-4000-8000-000000000002");
  private static final UUID AGENT2 = UUID.fromString("a0000002-0000-4000-8000-000000000003");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  private FakeTicketStore tickets;
  private FakeAgentStore agents;
  private FakeNotifications notifications;
  private AgentService service;
  private Clock clock;

  private final MedmatePrincipal adminSuper =
      new MedmatePrincipal(SUPER, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal adminOps =
      new MedmatePrincipal(OPS, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal adminSupport =
      new MedmatePrincipal(AGENT, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    tickets = new FakeTicketStore();
    agents = new FakeAgentStore();
    notifications = new FakeNotifications();
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), true, 20, "Ravi Kumar", NOW));
    agents.put(new AgentProfile(AGENT2, List.of("PAYMENT"), true, 20, "Sneha Rao", NOW));
    agents.putEmail(AGENT, "ravi.kumar@nammamedmate.com");
    service = new AgentService(agents, tickets, notifications, clock);
  }

  @Test
  void ac001_rosterShowsLoadOnlineCsat() {
    seedOpen(AGENT, 2);
    seedResolvedWithCsat(AGENT, 5, NOW.minusSeconds(3600), NOW.minusSeconds(1800));
    Map<String, Object> data = service.listAgents(adminOps);
    assertThat(data.get("total_agents")).isEqualTo(2);
    assertThat(data.get("online_agents")).isEqualTo(2);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("agents");
    Map<String, Object> ravi =
        list.stream().filter(a -> AGENT.equals(a.get("id"))).findFirst().orElseThrow();
    assertThat(ravi.get("open_load")).isEqualTo(2);
    assertThat(ravi.get("is_online")).isEqualTo(true);
    assertThat(ravi.get("csat_score")).isEqualTo(5.0);
  }

  @Test
  void ac002_agentAtCapNotSuggested() {
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), true, 20, "Ravi Kumar", NOW));
    seedOpen(AGENT, 20);
    agents.put(new AgentProfile(AGENT2, List.of("ORDER"), false, 20, "Offline", NOW));
    UUID ticketId = seedUnassignedTicket(TicketCategory.ORDER);
    Map<String, Object> suggest = service.suggestAssignment(adminOps, ticketId);
    assertThat(suggest.get("overflow")).isEqualTo(true);
    assertThat(suggest.get("suggested_agent")).isNull();
    assertThat(service.pickAutoAssign(TicketCategory.ORDER)).isNull();
  }

  @Test
  void ac003_goingOnlineBecomesEligible() {
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), false, 20, "Ravi Kumar", NOW));
    agents.put(new AgentProfile(AGENT2, List.of("PAYMENT"), false, 20, "Sneha Rao", NOW));
    assertThat(service.pickAutoAssign(TicketCategory.ORDER)).isNull();
    service.toggleStatus(adminSupport, AGENT, true);
    assertThat(service.pickAutoAssign(TicketCategory.ORDER)).isEqualTo(AGENT);
  }

  @Test
  void ac004_supportCannotToggleOthers() {
    assertThatThrownBy(() -> service.toggleStatus(adminSupport, AGENT2, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    Map<String, Object> own = service.toggleStatus(adminSupport, AGENT, false);
    assertThat(own.get("is_online")).isEqualTo(false);
  }

  @Test
  void ac005_suggestPrefersSpecialtyMatch() {
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), true, 20, "Ravi", NOW));
    agents.put(new AgentProfile(AGENT2, List.of("PAYMENT"), true, 20, "Sneha", NOW));
    UUID ticketId = seedUnassignedTicket(TicketCategory.ORDER);
    Map<String, Object> suggest = service.suggestAssignment(adminOps, ticketId);
    @SuppressWarnings("unchecked")
    Map<String, Object> suggested = (Map<String, Object>) suggest.get("suggested_agent");
    assertThat(suggested.get("id")).isEqualTo(AGENT);
    assertThat(suggested.get("specialty_match")).isEqualTo(true);
  }

  @Test
  void ac006_overflowWhenAllAtCapacity() {
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), true, 1, "Ravi", NOW));
    agents.put(new AgentProfile(AGENT2, List.of("ORDER"), true, 1, "Sneha", NOW));
    seedOpen(AGENT, 1);
    seedOpen(AGENT2, 1);
    UUID ticketId = seedUnassignedTicket(TicketCategory.ORDER);
    Map<String, Object> suggest = service.suggestAssignment(adminSuper, ticketId);
    assertThat(suggest.get("overflow")).isEqualTo(true);
    assertThat(suggest.get("suggested_agent")).isNull();
  }

  @Test
  void ac007_detailSlaBreachCountThisWeek() {
    Instant due = NOW.minusSeconds(3600);
    Ticket open =
        new Ticket(
            Ids.newId(),
            "TKT-20260724-000001",
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "x",
            TicketStatus.OPEN,
            TicketPriority.HIGH,
            SlaLevel.L1,
            due,
            due,
            due.plusSeconds(99999),
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
            NOW.minusSeconds(7200),
            NOW);
    tickets.insert(open);
    Map<String, Object> detail = service.getDetail(adminOps, AGENT);
    assertThat(detail.get("sla_breach_count_this_week")).isEqualTo(1);
  }

  @Test
  void ac008_workloadBreakdownSumsToOpenLoad() {
    seedOpen(AGENT, 3);
    Map<String, Object> wl = service.getWorkload(adminSupport, AGENT);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> breakdown =
        (List<Map<String, Object>>) wl.get("open_tickets_breakdown");
    int sum = breakdown.stream().mapToInt(r -> (Integer) r.get("count")).sum();
    assertThat(sum).isEqualTo(3);
    assertThat(tickets.countOpenAssigned(AGENT)).isEqualTo(3);
  }

  @Test
  void ac009_avgHandleMinutesMeanResolvedMinusFirstResponse() {
    seedResolvedWithCsat(AGENT, 4, NOW.minusSeconds(30 * 60), NOW.minusSeconds(10 * 60));
    seedResolvedWithCsat(AGENT, 5, NOW.minusSeconds(40 * 60), NOW.minusSeconds(10 * 60));
    Map<String, Object> data = service.listAgents(adminOps);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("agents");
    Map<String, Object> ravi =
        list.stream().filter(a -> AGENT.equals(a.get("id"))).findFirst().orElseThrow();
    // (20 + 30) / 2 = 25.0
    assertThat(ravi.get("avg_handle_minutes")).isEqualTo(25.0);
  }

  @Test
  void ac010_weeklySnapshotAndEmail() {
    seedResolvedWithCsat(
        AGENT, 5, Instant.parse("2026-07-14T10:00:00Z"), Instant.parse("2026-07-14T10:30:00Z"));
    // NOW is Fri 2026-07-24; prior Mon week is 2026-07-13..2026-07-20
    int n = service.generateWeeklyPerformanceSnapshots();
    assertThat(n).isEqualTo(2);
    assertThat(agents.findSnapshot(AGENT, LocalDate.parse("2026-07-13"))).isPresent();
    AgentWeeklyPerformanceScheduler sched = new AgentWeeklyPerformanceScheduler(service);
    sched.snapshotPriorWeek();
  }

  @Test
  void nullTrendFieldsAndOfflineRosterAndEmptyWorkload() {
    agents.put(new AgentProfile(AGENT2, List.of("PAYMENT"), false, 20, "Offline", NOW));
    Map<String, Object> roster = service.listAgents(adminOps);
    assertThat(roster.get("online_agents")).isEqualTo(1);
    agents.upsertSnapshot(
        new AgentPerformanceSnapshot(
            Ids.newId(), AGENT, LocalDate.parse("2026-07-06"), 0, null, null, 0, NOW));
    Map<String, Object> detail = service.getDetail(adminOps, AGENT);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> trend = (List<Map<String, Object>>) detail.get("performance_trend");
    assertThat(trend.stream().anyMatch(w -> w.get("csat") == null)).isTrue();
    Map<String, Object> wl = service.getWorkload(adminOps, AGENT2);
    @SuppressWarnings("unchecked")
    List<?> recent = (List<?>) wl.get("recent_resolved");
    assertThat(recent).isEmpty();
  }

  @Test
  void manyAlternativesAndResolutionOnlyBreach() {
    for (int i = 0; i < 7; i++) {
      UUID id = UUID.fromString(String.format("a0000002-0000-4000-8000-%012d", i + 10));
      agents.put(new AgentProfile(id, List.of("ORDER"), true, 20, "A" + i, NOW));
    }
    UUID ticketId = seedUnassignedTicket(TicketCategory.ORDER);
    Map<String, Object> suggest = service.suggestAssignment(adminOps, ticketId);
    @SuppressWarnings("unchecked")
    List<?> alts = (List<?>) suggest.get("alternative_agents");
    assertThat(alts).hasSize(5);

    Instant resolved = NOW.minusSeconds(60);
    Instant due = resolved.minusSeconds(120);
    tickets.insert(
        new Ticket(
            Ids.newId(),
            "TKT-RES-ONLY",
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "late-res",
            TicketStatus.RESOLVED,
            TicketPriority.HIGH,
            SlaLevel.L1,
            NOW.plusSeconds(3600),
            NOW.plusSeconds(3600),
            due,
            AGENT,
            TicketChannel.APP,
            NOW.minusSeconds(600),
            resolved,
            "late",
            3,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW.minusSeconds(700),
            resolved));
    Map<String, Object> detail = service.getDetail(adminOps, AGENT);
    assertThat((Integer) detail.get("sla_breach_count_this_week")).isGreaterThanOrEqualTo(1);
  }

  @Test
  void handleMinutesNullResolvedAtBranch() {
    TicketStore custom = org.mockito.Mockito.mock(TicketStore.class);
    org.mockito.Mockito.when(custom.countUnassignedOpen()).thenReturn(0);
    org.mockito.Mockito.when(custom.countOpenAssigned(org.mockito.ArgumentMatchers.any()))
        .thenReturn(0);
    org.mockito.Mockito.when(
            custom.listResolvedByAgent(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new Ticket(
                    Ids.newId(),
                    "TKT-NULL-RES",
                    CUST,
                    null,
                    null,
                    TicketCategory.ORDER,
                    "x",
                    TicketStatus.RESOLVED,
                    TicketPriority.MEDIUM,
                    SlaLevel.L2,
                    NOW,
                    NOW,
                    NOW,
                    AGENT,
                    TicketChannel.APP,
                    NOW.minusSeconds(60),
                    null,
                    "x",
                    5,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW.minusSeconds(120),
                    NOW)));
    AgentService svc = new AgentService(agents, custom, notifications, clock);
    Map<String, Object> data = svc.listAgents(adminOps);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("agents");
    Map<String, Object> ravi =
        list.stream().filter(a -> AGENT.equals(a.get("id"))).findFirst().orElseThrow();
    assertThat(ravi.get("avg_handle_minutes")).isNull();
  }

  @Test
  void handledTodayUsesIstCalendarDay() {
    // IST day for 2026-07-24T10:00Z is 2026-07-24; resolve within IST day
    seedResolvedWithCsat(AGENT, 5, NOW.minusSeconds(120), NOW.minusSeconds(60));
    Map<String, Object> data = service.listAgents(adminOps);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("agents");
    Map<String, Object> ravi =
        list.stream().filter(a -> AGENT.equals(a.get("id"))).findFirst().orElseThrow();
    assertThat(ravi.get("handled_today")).isEqualTo(1);
  }

  @Test
  void csatTiebreakPrefersHigher() {
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), true, 20, "LowCsat", NOW));
    agents.put(new AgentProfile(AGENT2, List.of("ORDER"), true, 20, "HighCsat", NOW));
    seedResolvedWithCsat(AGENT, 3, NOW.minusSeconds(3600), NOW.minusSeconds(1800));
    seedResolvedWithCsat(AGENT2, 5, NOW.minusSeconds(3600), NOW.minusSeconds(1800));
    assertThat(service.pickAutoAssign(TicketCategory.ORDER)).isEqualTo(AGENT2);
  }

  @Test
  void validationAndNotFoundBranches() {
    assertThatThrownBy(() -> service.listAgents(adminSupport))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.toggleStatus(adminOps, UUID.randomUUID(), true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("AGENT_NOT_FOUND");
    assertThatThrownBy(() -> service.toggleStatus(adminOps, AGENT, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.suggestAssignment(adminOps, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.suggestAssignment(adminOps, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("TICKET_NOT_FOUND");
    assertThatThrownBy(() -> service.getWorkload(adminSupport, AGENT2))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.getDetail(adminOps, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("AGENT_NOT_FOUND");
    agents.upsertSnapshot(
        new AgentPerformanceSnapshot(
            Ids.newId(),
            AGENT,
            LocalDate.parse("2026-07-13"),
            10,
            BigDecimal.valueOf(18.4),
            BigDecimal.valueOf(4.6),
            1,
            NOW));
    Map<String, Object> detail = service.getDetail(adminOps, AGENT);
    assertThat(detail.get("email")).isEqualTo("ravi.kumar@nammamedmate.com");
    @SuppressWarnings("unchecked")
    List<?> trend = (List<?>) detail.get("performance_trend");
    assertThat(trend).isNotEmpty();
  }

  private void seedOpen(UUID agentId, int n) {
    for (int i = 0; i < n; i++) {
      tickets.insert(
          new Ticket(
              Ids.newId(),
              "TKT-20260724-" + String.format("%06d", i + 1),
              CUST,
              null,
              null,
              TicketCategory.ORDER,
              "open",
              TicketStatus.IN_PROGRESS,
              TicketPriority.HIGH,
              SlaLevel.L1,
              NOW.plusSeconds(3600),
              NOW.plusSeconds(3600),
              NOW.plusSeconds(99999),
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
              NOW,
              NOW));
    }
  }

  private void seedResolvedWithCsat(
      UUID agentId, int csat, Instant firstResponse, Instant resolved) {
    tickets.insert(
        new Ticket(
            Ids.newId(),
            "TKT-RES-" + Ids.newId().toString().substring(0, 8),
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "done",
            TicketStatus.RESOLVED,
            TicketPriority.MEDIUM,
            SlaLevel.L2,
            firstResponse.plusSeconds(3600),
            firstResponse.plusSeconds(3600),
            resolved.plusSeconds(3600),
            agentId,
            TicketChannel.APP,
            firstResponse,
            resolved,
            "fixed",
            csat,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            firstResponse.minusSeconds(60),
            resolved));
  }

  private UUID seedUnassignedTicket(TicketCategory category) {
    UUID id = Ids.newId();
    tickets.insert(
        new Ticket(
            id,
            "TKT-20260724-999999",
            CUST,
            null,
            null,
            category,
            "need assign",
            TicketStatus.OPEN,
            TicketPriority.MEDIUM,
            SlaLevel.L2,
            NOW.plusSeconds(3600),
            NOW.plusSeconds(3600),
            NOW.plusSeconds(99999),
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
            NOW));
    return id;
  }
}
