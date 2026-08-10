package com.nammamedmate.support.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.port.out.AgentStore;
import com.nammamedmate.support.application.port.out.AutomationEscalatePort;
import com.nammamedmate.support.application.port.out.CustomerLookupPort;
import com.nammamedmate.support.application.port.out.EscalationMatrixStore;
import com.nammamedmate.support.application.port.out.EscalationMatrixStore.RulePatch;
import com.nammamedmate.support.application.port.out.SlaPolicyStore;
import com.nammamedmate.support.application.port.out.SupportAuditPort;
import com.nammamedmate.support.application.port.out.TicketStore;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.EscalationRule;
import com.nammamedmate.support.domain.SlaAdherence;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.SlaPolicy;
import com.nammamedmate.support.domain.Ticket;
import java.time.Clock;
import java.time.Instant;
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
public class SlaService {

  private static final Set<AuthRole> POLICY_READ =
      EnumSet.of(
          AuthRole.ADMIN_SUPER,
          AuthRole.ADMIN_OPERATIONS,
          AuthRole.ADMIN_SUPPORT,
          AuthRole.ADMIN_FINANCE);
  private static final Set<AuthRole> BREACH_READ =
      EnumSet.of(
          AuthRole.ADMIN_SUPER,
          AuthRole.ADMIN_OPERATIONS,
          AuthRole.ADMIN_SUPPORT,
          AuthRole.ADMIN_FINANCE);
  private static final Set<AuthRole> MATRIX_READ =
      EnumSet.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);

  private final SlaPolicyStore policies;
  private final EscalationMatrixStore matrix;
  private final TicketStore tickets;
  private final AgentStore agents;
  private final CustomerLookupPort customers;
  private final AutomationEscalatePort automation;
  private final SupportAuditPort audit;
  private final Clock clock;

  public SlaService(
      SlaPolicyStore policies,
      EscalationMatrixStore matrix,
      TicketStore tickets,
      AgentStore agents,
      CustomerLookupPort customers,
      AutomationEscalatePort automation,
      SupportAuditPort audit,
      Clock clock) {
    this.policies = policies;
    this.matrix = matrix;
    this.tickets = tickets;
    this.agents = agents;
    this.customers = customers;
    this.automation = automation;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> listPolicies(MedmatePrincipal principal) {
    requireRole(principal, POLICY_READ);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (SlaPolicy p : policies.listAll()) {
      rows.add(toPolicyMap(p));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("sla_policies", rows);
    return data;
  }

  @Transactional
  public Map<String, Object> updatePolicy(
      MedmatePrincipal principal,
      UUID id,
      Integer firstResponseMinutes,
      Integer resolutionMinutes) {
    requirePrincipal(principal);
    if (principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super may update SLA policies", 403);
    }
    if (id == null) {
      throw new AppException("SLA_POLICY_NOT_FOUND", "Policy ID does not exist", 404);
    }
    SlaPolicy before =
        policies
            .findById(id)
            .orElseThrow(
                () -> new AppException("SLA_POLICY_NOT_FOUND", "Policy ID does not exist", 404));
    Instant now = clock.instant();
    SlaPolicy updated =
        policies.update(
            id, firstResponseMinutes, resolutionMinutes, null, principal.subject(), now);
    audit.appendSlaPolicy(
        principal.subject(),
        principal.role().name(),
        id,
        Map.of(
            "first_response_sla_minutes",
            before.firstResponseSlaMinutes(),
            "resolution_sla_minutes",
            before.resolutionSlaMinutes()),
        Map.of(
            "first_response_sla_minutes",
            updated.firstResponseSlaMinutes(),
            "resolution_sla_minutes",
            updated.resolutionSlaMinutes()));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("first_response_sla_minutes", updated.firstResponseSlaMinutes());
    data.put("resolution_sla_minutes", updated.resolutionSlaMinutes());
    data.put("updated_at", updated.updatedAt());
    data.put("updated_by", updated.updatedBy());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> listBreaches(
      MedmatePrincipal principal, String breachType, String slaLevel, UUID assignedAgentId) {
    requireRole(principal, BREACH_READ);
    Instant now = clock.instant();
    String typeFilter = blank(breachType) ? null : breachType.trim().toUpperCase(Locale.ROOT);
    SlaLevel levelFilter = blank(slaLevel) ? null : parseLevel(slaLevel);
    List<Map<String, Object>> breaches = new ArrayList<>();
    for (Ticket t : tickets.findOpenForSlaScan(5_000)) {
      if (assignedAgentId != null && !assignedAgentId.equals(t.assignedAgentId())) {
        continue;
      }
      if (levelFilter != null && t.slaLevel() != levelFilter) {
        continue;
      }
      if (typeFilter == null || "FIRST_RESPONSE".equals(typeFilter)) {
        if (t.firstResponseBreached(now)) {
          breaches.add(toBreachMap(t, "FIRST_RESPONSE", t.minutesBreachedFirstResponse(now), now));
        }
      }
      if (typeFilter == null || "RESOLUTION".equals(typeFilter)) {
        if (t.resolutionBreached(now)) {
          breaches.add(toBreachMap(t, "RESOLUTION", t.minutesBreachedResolution(now), now));
        }
      }
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("breach_count", breaches.size());
    data.put("breaches", breaches);
    data.put("last_refreshed_at", now);
    TicketStore.ResolvedSlaStats stats = tickets.resolvedSlaStats();
    data.put("sla_adherence_pct", SlaAdherence.pct(stats.withinSla(), stats.totalResolved()));
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getEscalationMatrix(MedmatePrincipal principal) {
    requireRole(principal, MATRIX_READ);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (EscalationRule r : matrix.listAll()) {
      rows.add(toMatrixMap(r));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("escalation_matrix", rows);
    return data;
  }

  @Transactional
  public Map<String, Object> updateEscalationMatrix(
      MedmatePrincipal principal, List<RulePatch> patches) {
    requirePrincipal(principal);
    if (principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super may update escalation matrix", 403);
    }
    Instant now = clock.instant();
    List<Map<String, Object>> before =
        matrix.listAll().stream().map(SlaService::toMatrixMap).toList();
    List<SlaLevel> updated =
        matrix.updateRules(patches == null ? List.of() : patches, principal.subject(), now);
    List<Map<String, Object>> after =
        matrix.listAll().stream().map(SlaService::toMatrixMap).toList();
    audit.appendEscalationMatrix(
        principal.subject(),
        principal.role().name(),
        Map.of("escalation_matrix", before),
        Map.of("escalation_matrix", after));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("updated_levels", updated.stream().map(Enum::name).toList());
    data.put("updated_at", now);
    data.put("updated_by", principal.subject());
    return data;
  }

  /**
   * Detect breaches and escalate via automation engine using matrix auto_escalate_after_minutes.
   */
  @Transactional
  public int processSlaBreaches(int limit) {
    Instant now = clock.instant();
    int n = 0;
    for (Ticket t : tickets.findOpenForSlaScan(limit)) {
      if (!(t.firstResponseBreached(now) || t.resolutionBreached(now))) {
        continue;
      }
      long minutes =
          Math.max(t.minutesBreachedFirstResponse(now), t.minutesBreachedResolution(now));
      EscalationRule rule = matrix.findByLevel(t.slaLevel()).orElse(null);
      int threshold =
          rule == null ? defaultThreshold(t.slaLevel()) : rule.autoEscalateAfterMinutes();
      if (minutes < threshold) {
        continue;
      }
      if (t.slaLevel() == SlaLevel.L4) {
        if (t.slaL4NotifiedAt() == null) {
          String team = rule == null ? "Senior Ops Manager" : rule.assignedTeam();
          List<String> channels =
              rule == null ? List.of("IN_APP", "WHATSAPP", "CALL") : rule.notificationChannels();
          automation.notifyL4SeniorOps(t.id(), team, channels);
          n++;
        }
        continue;
      }
      SlaLevel from = t.slaLevel();
      SlaLevel to = from.next();
      automation.escalateOnSlaBreach(t.id(), from.name(), to.name());
      n++;
    }
    return n;
  }

  private Map<String, Object> toBreachMap(Ticket t, String type, long minutes, Instant now) {
    Instant due = "FIRST_RESPONSE".equals(type) ? t.firstResponseDueAt() : t.resolutionDueAt();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ticket_id", t.ticketId());
    m.put("category", t.category().name());
    m.put("customer_name", customers.displayName(t.customerId()).orElse("Customer"));
    m.put(
        "assigned_agent",
        t.assignedAgentId() == null
            ? null
            : agents.findById(t.assignedAgentId()).map(AgentProfile::displayName).orElse(null));
    m.put("sla_level", t.slaLevel().name());
    m.put("breach_type", type);
    m.put("breached_at", due);
    m.put("minutes_breached", minutes);
    m.put("current_status", t.status().name());
    return m;
  }

  private static Map<String, Object> toPolicyMap(SlaPolicy p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", p.id());
    m.put("category", p.category());
    m.put("priority", p.priority());
    m.put("first_response_sla_minutes", p.firstResponseSlaMinutes());
    m.put("resolution_sla_hours", p.resolutionSlaHours());
    m.put("resolution_sla_minutes", p.resolutionSlaMinutes());
    m.put("sla_level", p.slaLevel().name());
    m.put("escalation_levels", escalationLevelsFrom(p.slaLevel()));
    return m;
  }

  private static List<String> escalationLevelsFrom(SlaLevel level) {
    List<String> out = new ArrayList<>();
    SlaLevel cur = level;
    while (true) {
      out.add(cur.name());
      if (cur == SlaLevel.L4) {
        break;
      }
      cur = cur.next();
    }
    return out;
  }

  private static Map<String, Object> toMatrixMap(EscalationRule r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("level", r.level().name());
    m.put("criteria", r.criteria());
    m.put("assigned_team", r.assignedTeam());
    m.put("notification_channel", r.notificationChannels());
    m.put("auto_escalate_after_minutes", r.autoEscalateAfterMinutes());
    return m;
  }

  private static int defaultThreshold(SlaLevel level) {
    return switch (level) {
      case L1 -> 30;
      case L2 -> 120;
      case L3 -> 480;
      case L4 -> 1440;
    };
  }

  private static SlaLevel parseLevel(String raw) {
    try {
      return SlaLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid sla_level", 400);
    }
  }

  private static void requireRole(MedmatePrincipal principal, Set<AuthRole> roles) {
    requirePrincipal(principal);
    if (!roles.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requirePrincipal(MedmatePrincipal principal) {
    if (principal != null) {
      return;
    }
    throw new AppException("UNAUTHORIZED", "Authentication required", 401);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
