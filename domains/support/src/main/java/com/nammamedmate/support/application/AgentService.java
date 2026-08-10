package com.nammamedmate.support.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.AgentAssignment.Ranked;
import com.nammamedmate.support.application.port.out.AgentStore;
import com.nammamedmate.support.application.port.out.NotificationDispatchPort;
import com.nammamedmate.support.application.port.out.TicketStore;
import com.nammamedmate.support.domain.AgentPerformanceSnapshot;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final Set<AuthRole> ROSTER_ROLES =
      EnumSet.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final Set<AuthRole> AGENT_ROLES =
      EnumSet.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_SUPPORT);
  private static final int CSAT_WINDOW_DAYS = 30;
  private static final int AT_RISK_MINUTES = 60;

  private final AgentStore agents;
  private final TicketStore tickets;
  private final NotificationDispatchPort notifications;
  private final Clock clock;

  public AgentService(
      AgentStore agents, TicketStore tickets, NotificationDispatchPort notifications, Clock clock) {
    this.agents = agents;
    this.tickets = tickets;
    this.notifications = notifications;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> listAgents(MedmatePrincipal principal) {
    requireRoles(principal, ROSTER_ROLES);
    Instant now = clock.instant();
    Instant csatSince = now.minus(CSAT_WINDOW_DAYS, ChronoUnit.DAYS);
    LocalDate todayIst = LocalDate.now(clock.withZone(IST));
    Instant dayStart = todayIst.atStartOfDay(IST).toInstant();
    Instant dayEnd = todayIst.plusDays(1).atStartOfDay(IST).toInstant();

    List<AgentProfile> roster = agents.listAll();
    List<Map<String, Object>> items = new ArrayList<>();
    int online = 0;
    for (AgentProfile a : roster) {
      if (a.online()) {
        online++;
      }
      items.add(toRosterItem(a, now, csatSince, dayStart, dayEnd));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total_agents", roster.size());
    data.put("online_agents", online);
    data.put("overflow_queue_count", tickets.countUnassignedOpen());
    data.put("agents", items);
    return data;
  }

  @Transactional
  public Map<String, Object> toggleStatus(
      MedmatePrincipal principal, UUID agentId, Boolean isOnline) {
    requireRoles(principal, AGENT_ROLES);
    if (agentId == null) {
      throw new AppException("VALIDATION_ERROR", "agent id is required", 400);
    }
    if (isOnline == null) {
      throw new AppException("VALIDATION_ERROR", "is_online is required", 400);
    }
    if (principal.role() == AuthRole.ADMIN_SUPPORT && !principal.subject().equals(agentId)) {
      throw new AppException("FORBIDDEN", "admin_support can only toggle own status", 403);
    }
    agents
        .findById(agentId)
        .orElseThrow(() -> new AppException("AGENT_NOT_FOUND", "Agent ID does not exist", 404));
    Instant now = clock.instant();
    AgentProfile updated = agents.updateOnline(agentId, isOnline, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("agent_id", updated.adminUserId());
    data.put("is_online", updated.online());
    data.put("toggled_at", now);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getDetail(MedmatePrincipal principal, UUID agentId) {
    requireRoles(principal, ROSTER_ROLES);
    AgentProfile agent = requireAgent(agentId);
    Instant now = clock.instant();
    Instant csatSince = now.minus(CSAT_WINDOW_DAYS, ChronoUnit.DAYS);
    LocalDate todayIst = LocalDate.now(clock.withZone(IST));
    Instant dayStart = todayIst.atStartOfDay(IST).toInstant();
    Instant dayEnd = todayIst.plusDays(1).atStartOfDay(IST).toInstant();
    LocalDate weekStart = todayIst.with(DayOfWeek.MONDAY);
    Instant weekStartInst = weekStart.atStartOfDay(IST).toInstant();

    List<Ticket> resolved30 = tickets.listResolvedByAgent(agentId, csatSince, now);
    List<Ticket> resolvedToday = tickets.listResolvedByAgent(agentId, dayStart, dayEnd);
    List<Ticket> open = tickets.listAssignedOpen(agentId);
    int slaBreachCount = countSlaBreachesThisWeek(agentId, weekStartInst, now);

    List<Map<String, Object>> atRisk = new ArrayList<>();
    Instant riskHorizon = now.plus(AT_RISK_MINUTES, ChronoUnit.MINUTES);
    for (Ticket t : open) {
      if (t.firstResponseAt() != null) {
        continue;
      }
      Instant due = t.firstResponseDueAt();
      if (due.isAfter(riskHorizon)) {
        continue;
      }
      long minutes = Math.max(0L, Duration.between(now, due).toMinutes());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("ticket_id", t.ticketId());
      row.put("category", t.category().name());
      row.put("sla_due_at", due);
      row.put("minutes_to_breach", minutes);
      atRisk.add(row);
    }

    List<Map<String, Object>> trend = new ArrayList<>();
    for (AgentPerformanceSnapshot s : agents.listSnapshots(agentId)) {
      Map<String, Object> w = new LinkedHashMap<>();
      w.put("week", isoWeekLabel(s.weekStart()));
      w.put("csat", s.csatScoreAvg() == null ? null : s.csatScoreAvg().doubleValue());
      w.put("handled", s.ticketsHandled());
      w.put(
          "avg_handle_min",
          s.avgHandleMinutes() == null ? null : s.avgHandleMinutes().doubleValue());
      trend.add(w);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", agent.adminUserId());
    data.put("name", agent.displayName());
    data.put("email", agents.findEmail(agentId).orElse(null));
    data.put("specialties", agent.specialties());
    data.put("is_online", agent.online());
    data.put("open_load", tickets.countOpenAssigned(agentId));
    data.put("handled_today", resolvedToday.size());
    data.put("avg_handle_minutes", avgHandleMinutes(resolved30));
    data.put("csat_score", avgCsat(resolved30));
    data.put("sla_breach_count_this_week", slaBreachCount);
    data.put("at_risk_tickets", atRisk);
    data.put("performance_trend", trend);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getWorkload(MedmatePrincipal principal, UUID agentId) {
    requireRoles(principal, AGENT_ROLES);
    if (principal.role() == AuthRole.ADMIN_SUPPORT && !principal.subject().equals(agentId)) {
      throw new AppException("FORBIDDEN", "admin_support can only view own workload", 403);
    }
    requireAgent(agentId);
    List<Ticket> open = tickets.listAssignedOpen(agentId);
    Map<String, Integer> breakdownCounts = new LinkedHashMap<>();
    for (Ticket t : open) {
      String key = t.category().name() + "|" + t.priority().name();
      breakdownCounts.merge(key, 1, Integer::sum);
    }
    List<Map<String, Object>> breakdown = new ArrayList<>();
    for (Map.Entry<String, Integer> e : breakdownCounts.entrySet()) {
      String[] parts = e.getKey().split("\\|", 2);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("category", parts[0]);
      row.put("priority", parts[1]);
      row.put("count", e.getValue());
      breakdown.add(row);
    }

    Instant now = clock.instant();
    Instant since = now.minus(CSAT_WINDOW_DAYS, ChronoUnit.DAYS);
    List<Ticket> resolved = tickets.listResolvedByAgent(agentId, since, now);
    List<Map<String, Object>> recent = new ArrayList<>();
    int from = Math.max(0, resolved.size() - 20);
    for (int i = resolved.size() - 1; i >= from; i--) {
      Ticket t = resolved.get(i);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("ticket_id", t.ticketId());
      row.put("category", t.category().name());
      row.put("handle_minutes", handleMinutes(t));
      row.put("csat_score", t.csatScore());
      recent.add(row);
    }

    int b0 = 0, b15 = 0, b30 = 0, b60 = 0;
    for (Ticket t : resolved) {
      Long mins = handleMinutes(t);
      if (mins == null) {
        continue;
      }
      if (mins < 15) {
        b0++;
      } else if (mins < 30) {
        b15++;
      } else if (mins < 60) {
        b30++;
      } else {
        b60++;
      }
    }
    List<Map<String, Object>> dist =
        List.of(
            Map.of("bucket", "0-15 min", "count", b0),
            Map.of("bucket", "15-30 min", "count", b15),
            Map.of("bucket", "30-60 min", "count", b30),
            Map.of("bucket", "60+ min", "count", b60));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("agent_id", agentId);
    data.put("open_tickets_breakdown", breakdown);
    data.put("recent_resolved", recent);
    data.put("handle_time_distribution", dist);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> suggestAssignment(MedmatePrincipal principal, UUID ticketId) {
    requireRoles(principal, AGENT_ROLES);
    if (ticketId == null) {
      throw new AppException("VALIDATION_ERROR", "ticket_id is required", 400);
    }
    Ticket ticket =
        tickets
            .findById(ticketId)
            .orElseThrow(
                () -> new AppException("TICKET_NOT_FOUND", "Ticket ID does not exist", 404));
    Instant now = clock.instant();
    Instant csatSince = now.minus(CSAT_WINDOW_DAYS, ChronoUnit.DAYS);
    List<Ranked> ranked =
        AgentAssignment.rankEligible(
            agents.listOnline(),
            ticket.category(),
            a -> tickets.countOpenAssigned(a.adminUserId()),
            a -> {
              Double c = avgCsat(tickets.listResolvedByAgent(a.adminUserId(), csatSince, now));
              return c == null ? 0.0 : c;
            });
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ticket_id", ticket.id());
    if (ranked.isEmpty()) {
      data.put("suggested_agent", null);
      data.put("alternative_agents", List.of());
      data.put("overflow", true);
      return data;
    }
    data.put("suggested_agent", toSuggestItem(ranked.getFirst()));
    List<Map<String, Object>> alts = new ArrayList<>();
    int altMax = Math.min(ranked.size(), 6);
    for (int i = 1; i < altMax; i++) {
      alts.add(toSuggestItem(ranked.get(i)));
    }
    data.put("alternative_agents", alts);
    data.put("overflow", false);
    return data;
  }

  /** Used by TicketService auto-assign — specialty-matched only, then load + CSAT. */
  public UUID pickAutoAssign(TicketCategory category) {
    Instant now = clock.instant();
    Instant csatSince = now.minus(CSAT_WINDOW_DAYS, ChronoUnit.DAYS);
    List<Ranked> ranked =
        AgentAssignment.rankEligible(
            agents.listOnline(),
            category,
            a -> tickets.countOpenAssigned(a.adminUserId()),
            a -> {
              Double c = avgCsat(tickets.listResolvedByAgent(a.adminUserId(), csatSince, now));
              return c == null ? 0.0 : c;
            });
    return ranked.stream()
        .filter(Ranked::specialtyMatch)
        .map(r -> r.agent().adminUserId())
        .findFirst()
        .orElse(null);
  }

  @Transactional
  public int generateWeeklyPerformanceSnapshots() {
    Instant now = clock.instant();
    LocalDate todayIst = LocalDate.now(clock.withZone(IST));
    LocalDate thisWeekMon = todayIst.with(DayOfWeek.MONDAY);
    LocalDate priorWeekStart = thisWeekMon.minusWeeks(1);
    LocalDate priorWeekEnd = thisWeekMon;
    Instant from = priorWeekStart.atStartOfDay(IST).toInstant();
    Instant to = priorWeekEnd.atStartOfDay(IST).toInstant();

    int n = 0;
    for (AgentProfile agent : agents.listAll()) {
      List<Ticket> resolved = tickets.listResolvedByAgent(agent.adminUserId(), from, to);
      Double avgHandle = avgHandleMinutes(resolved);
      Double csat = avgCsat(resolved);
      int breaches = countSlaBreachesInResolved(resolved);
      AgentPerformanceSnapshot snap =
          new AgentPerformanceSnapshot(
              Ids.newId(),
              agent.adminUserId(),
              priorWeekStart,
              resolved.size(),
              avgHandle == null
                  ? null
                  : BigDecimal.valueOf(avgHandle).setScale(2, RoundingMode.HALF_UP),
              csat == null ? null : BigDecimal.valueOf(csat).setScale(2, RoundingMode.HALF_UP),
              breaches,
              now);
      agents.upsertSnapshot(snap);
      n++;
    }
    notifications.notifyWeeklyAgentPerformance(priorWeekStart, n);
    return n;
  }

  private Map<String, Object> toRosterItem(
      AgentProfile a, Instant now, Instant csatSince, Instant dayStart, Instant dayEnd) {
    List<Ticket> resolved30 = tickets.listResolvedByAgent(a.adminUserId(), csatSince, now);
    List<Ticket> resolvedToday = tickets.listResolvedByAgent(a.adminUserId(), dayStart, dayEnd);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", a.adminUserId());
    m.put("name", a.displayName());
    m.put("role", "admin_support");
    m.put("specialties", a.specialties());
    m.put("is_online", a.online());
    m.put("open_load", tickets.countOpenAssigned(a.adminUserId()));
    m.put("handled_today", resolvedToday.size());
    m.put("avg_handle_minutes", avgHandleMinutes(resolved30));
    m.put("csat_score", avgCsat(resolved30));
    return m;
  }

  private static Map<String, Object> toSuggestItem(Ranked r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.agent().adminUserId());
    m.put("name", r.agent().displayName());
    m.put("is_online", r.agent().online());
    m.put("open_load", r.openLoad());
    m.put("csat_score", r.csat() == 0.0 ? null : r.csat());
    m.put("specialty_match", r.specialtyMatch());
    return m;
  }

  private int countSlaBreachesThisWeek(UUID agentId, Instant weekStart, Instant now) {
    int openBreaches =
        (int) tickets.listAssignedOpen(agentId).stream().filter(t -> t.slaBreached(now)).count();
    int resolvedBreaches =
        countSlaBreachesInResolved(tickets.listResolvedByAgent(agentId, weekStart, now));
    return openBreaches + resolvedBreaches;
  }

  private static int countSlaBreachesInResolved(List<Ticket> resolved) {
    int n = 0;
    for (Ticket t : resolved) {
      boolean fr =
          t.firstResponseAt() != null && t.firstResponseAt().isAfter(t.firstResponseDueAt());
      boolean res = t.resolvedAt().isAfter(t.resolutionDueAt());
      if (fr || res) {
        n++;
      }
    }
    return n;
  }

  private static Double avgHandleMinutes(List<Ticket> resolved) {
    long sum = 0;
    int n = 0;
    for (Ticket t : resolved) {
      Long mins = handleMinutes(t);
      if (mins == null) {
        continue;
      }
      sum += mins;
      n++;
    }
    if (n == 0) {
      return null;
    }
    return Math.round((sum * 10.0) / n) / 10.0;
  }

  private static Long handleMinutes(Ticket t) {
    if (t.resolvedAt() == null || t.firstResponseAt() == null) {
      return null;
    }
    return Math.max(0L, Duration.between(t.firstResponseAt(), t.resolvedAt()).toMinutes());
  }

  private static Double avgCsat(List<Ticket> resolved) {
    long sum = 0;
    int n = 0;
    for (Ticket t : resolved) {
      if (t.csatScore() == null) {
        continue;
      }
      sum += t.csatScore();
      n++;
    }
    if (n == 0) {
      return null;
    }
    return Math.round((sum * 10.0) / n) / 10.0;
  }

  private static String isoWeekLabel(LocalDate weekStart) {
    WeekFields wf = WeekFields.ISO;
    int week = weekStart.get(wf.weekOfWeekBasedYear());
    int year = weekStart.get(wf.weekBasedYear());
    return String.format(Locale.ROOT, "%d-W%02d", year, week);
  }

  private AgentProfile requireAgent(UUID agentId) {
    if (agentId == null) {
      throw new AppException("VALIDATION_ERROR", "agent id is required", 400);
    }
    return agents
        .findById(agentId)
        .orElseThrow(() -> new AppException("AGENT_NOT_FOUND", "Agent ID does not exist", 404));
  }

  private static void requireRoles(MedmatePrincipal principal, Set<AuthRole> roles) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (!roles.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }
}
