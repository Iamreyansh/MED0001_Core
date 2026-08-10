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

class AgentCoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID AGENT = UUID.fromString("a0000002-0000-4000-8000-000000000002");
  private static final UUID AGENT2 = UUID.fromString("a0000002-0000-4000-8000-000000000003");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID OPS = UUID.fromString("a0000001-0000-4000-8000-000000000010");

  private FakeTicketStore tickets;
  private FakeAgentStore agents;
  private AgentService service;

  private final MedmatePrincipal ops =
      new MedmatePrincipal(OPS, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    tickets = new FakeTicketStore();
    agents = new FakeAgentStore();
    FakeNotifications notifications = new FakeNotifications();
    agents.put(new AgentProfile(AGENT, List.of("ORDER"), true, 20, "Ravi", NOW));
    agents.put(new AgentProfile(AGENT2, List.of("ORDER"), true, 20, "Alt", NOW));
    service = new AgentService(agents, tickets, notifications, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void atRiskAndHandleBucketsAndAlternatives() {
    Instant dueSoon = NOW.plusSeconds(30 * 60);
    tickets.insert(
        new Ticket(
            Ids.newId(),
            "TKT-RISK-1",
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "risk",
            TicketStatus.OPEN,
            TicketPriority.HIGH,
            SlaLevel.L1,
            dueSoon,
            dueSoon,
            dueSoon.plusSeconds(99999),
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
            NOW));
    // already responded — skipped for at-risk
    tickets.insert(
        new Ticket(
            Ids.newId(),
            "TKT-RISK-2",
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "ok",
            TicketStatus.IN_PROGRESS,
            TicketPriority.LOW,
            SlaLevel.L1,
            dueSoon,
            dueSoon,
            dueSoon.plusSeconds(99999),
            AGENT,
            TicketChannel.APP,
            NOW.minusSeconds(60),
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
    // far due — skipped (>60 min)
    tickets.insert(
        new Ticket(
            Ids.newId(),
            "TKT-RISK-3",
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "far",
            TicketStatus.OPEN,
            TicketPriority.MEDIUM,
            SlaLevel.L2,
            NOW.plusSeconds(2 * 3600),
            NOW.plusSeconds(2 * 3600),
            NOW.plusSeconds(99999),
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
            NOW));

    seedResolvedHandle(AGENT, 10); // 0-15
    seedResolvedHandle(AGENT, 20); // 15-30
    seedResolvedHandle(AGENT, 45); // 30-60
    seedResolvedHandle(AGENT, 90); // 60+
    // missing first_response → null handle minutes bucket skip
    tickets.insert(
        new Ticket(
            Ids.newId(),
            "TKT-NOFR",
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "nofr",
            TicketStatus.RESOLVED,
            TicketPriority.MEDIUM,
            SlaLevel.L2,
            NOW,
            NOW,
            NOW,
            AGENT,
            TicketChannel.APP,
            null,
            NOW.minusSeconds(60),
            "x",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW.minusSeconds(120),
            NOW));

    Map<String, Object> detail = service.getDetail(ops, AGENT);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> atRisk = (List<Map<String, Object>>) detail.get("at_risk_tickets");
    assertThat(atRisk).hasSize(1);
    assertThat(atRisk.getFirst().get("ticket_id")).isEqualTo("TKT-RISK-1");

    Map<String, Object> wl = service.getWorkload(ops, AGENT);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> dist = (List<Map<String, Object>>) wl.get("handle_time_distribution");
    assertThat(dist.stream().mapToInt(r -> (Integer) r.get("count")).sum()).isEqualTo(4);

    UUID ticketId = Ids.newId();
    tickets.insert(
        new Ticket(
            ticketId,
            "TKT-SUG",
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "s",
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
    Map<String, Object> suggest = service.suggestAssignment(ops, ticketId);
    @SuppressWarnings("unchecked")
    List<?> alts = (List<?>) suggest.get("alternative_agents");
    assertThat(alts).isNotEmpty();
    assertThat(suggest.get("overflow")).isEqualTo(false);
  }

  @Test
  void unauthorizedAndNullAgentId() {
    assertThatThrownBy(() -> service.listAgents(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.toggleStatus(ops, null, true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.getDetail(ops, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void resolvedBreachCounting() {
    Instant frDue = NOW.minusSeconds(7200);
    Instant resolved = NOW.minusSeconds(3600);
    tickets.insert(
        new Ticket(
            Ids.newId(),
            "TKT-BREACH",
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "late",
            TicketStatus.RESOLVED,
            TicketPriority.HIGH,
            SlaLevel.L1,
            frDue,
            frDue,
            resolved.minusSeconds(60),
            AGENT,
            TicketChannel.APP,
            frDue.plusSeconds(100),
            resolved,
            "late",
            4,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            frDue.minusSeconds(60),
            resolved));
    Map<String, Object> detail = service.getDetail(ops, AGENT);
    assertThat((Integer) detail.get("sla_breach_count_this_week")).isGreaterThanOrEqualTo(1);
  }

  private void seedResolvedHandle(UUID agentId, long handleMinutes) {
    Instant resolved = NOW.minusSeconds(60);
    Instant first = resolved.minusSeconds(handleMinutes * 60);
    tickets.insert(
        new Ticket(
            Ids.newId(),
            "TKT-H-" + handleMinutes,
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "h",
            TicketStatus.RESOLVED,
            TicketPriority.MEDIUM,
            SlaLevel.L2,
            first.plusSeconds(3600),
            first.plusSeconds(3600),
            resolved.plusSeconds(3600),
            agentId,
            TicketChannel.APP,
            first,
            resolved,
            "ok",
            5,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            first.minusSeconds(10),
            resolved));
  }
}
