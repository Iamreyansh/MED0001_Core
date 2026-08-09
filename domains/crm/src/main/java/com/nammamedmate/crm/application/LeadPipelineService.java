package com.nammamedmate.crm.application;

import com.nammamedmate.crm.application.port.out.CrmLeadOutboxPort;
import com.nammamedmate.crm.application.port.out.EnsureMarketplaceLeadPort;
import com.nammamedmate.crm.application.port.out.SaasLeadStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.CrmLead;
import com.nammamedmate.crm.domain.CrmLeadActivity;
import com.nammamedmate.crm.domain.CrmMoney;
import com.nammamedmate.crm.domain.LeadSource;
import com.nammamedmate.crm.domain.LeadStage;
import com.nammamedmate.crm.domain.LostReason;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeadPipelineService implements EnsureMarketplaceLeadPort {

  private final SaasLeadStore leads;
  private final SaasPlanStore plans;
  private final SubscriptionService subscriptions;
  private final CrmLeadOutboxPort outbox;
  private final Clock clock;

  public LeadPipelineService(
      SaasLeadStore leads,
      SaasPlanStore plans,
      SubscriptionService subscriptions,
      CrmLeadOutboxPort outbox,
      Clock clock) {
    this.leads = leads;
    this.plans = plans;
    this.subscriptions = subscriptions;
    this.outbox = outbox;
    this.clock = clock;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public PagedResult list(
      MedmatePrincipal principal,
      String stage,
      UUID repId,
      String source,
      String q,
      Integer page,
      Integer limit) {
    requireAdminRead(principal);
    String stageFilter = stage == null || stage.isBlank() ? null : LeadStage.requireValid(stage);
    String sourceFilter =
        source == null || source.isBlank() ? null : LeadSource.requireValid(source);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    int offset = (p - 1) * lim;
    long total = leads.count(stageFilter, repId, sourceFilter, q);
    List<CrmLead> rows = leads.list(stageFilter, repId, sourceFilter, q, offset, lim);
    LocalDate monthStart = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
    Instant periodFrom = monthStart.atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant periodTo = clock.instant();
    SaasLeadStore.PipelineChips chips = leads.chips(periodFrom, periodTo);
    Map<String, Long> funnel = leads.openStageFunnel();
    Map<String, Object> stageFunnel = new LinkedHashMap<>();
    for (String s :
        List.of(
            LeadStage.NEW, LeadStage.CONTACTED, LeadStage.DEMO, LeadStage.TRIAL, LeadStage.WON)) {
      stageFunnel.put(s, funnel.getOrDefault(s, 0L));
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (CrmLead lead : rows) {
      items.add(toListItem(lead));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    Map<String, Object> chipMap = new LinkedHashMap<>();
    chipMap.put("open_leads", chips.openLeads());
    chipMap.put("pipeline_mrr_rs", CrmMoney.paiseToRupees(chips.pipelineMrrPaise()));
    chipMap.put(
        "weighted_forecast_mrr_rs", CrmMoney.paiseToRupees(chips.weightedForecastMrrPaise()));
    chipMap.put("avg_deal_mrr_rs", CrmMoney.paiseToRupees(chips.avgDealMrrPaise()));
    chipMap.put(
        "win_rate_pct", BigDecimal.valueOf(chips.winRatePct()).setScale(1, RoundingMode.HALF_UP));
    chipMap.put(
        "avg_sales_cycle_days",
        BigDecimal.valueOf(chips.avgSalesCycleDays()).setScale(0, RoundingMode.HALF_UP));
    data.put("chips", chipMap);
    data.put("stage_funnel", stageFunnel);
    data.put("leads", items);
    return new PagedResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal,
      String pharmacyName,
      String contactName,
      String phone,
      String email,
      String sourceRaw,
      String targetPlan,
      BigDecimal estimatedMrrRs,
      UUID assignedRepId,
      UUID pharmacyId) {
    requireAdminWrite(principal);
    Instant now = clock.instant();
    validateCreateFields(pharmacyName, contactName, phone);
    String source = LeadSource.requireValid(sourceRaw);
    if (leads.existsOpenByPhone(phone.trim(), null)) {
      throw new AppException("LEAD_ALREADY_EXISTS", "Pharmacy already has an open lead", 409);
    }
    if (pharmacyId != null && leads.existsOpenByPharmacyId(pharmacyId, null)) {
      throw new AppException("LEAD_ALREADY_EXISTS", "Pharmacy already has an open lead", 409);
    }
    UUID repId = resolveRep(assignedRepId);
    Long mrrPaise = estimatedMrrRs == null ? null : CrmMoney.rupeesToPaise(estimatedMrrRs);
    if (mrrPaise == null && targetPlan != null && !targetPlan.isBlank()) {
      mrrPaise =
          plans
              .findPlanByName(targetPlan.trim().toUpperCase(Locale.ROOT))
              .map(SaasPlan::priceMonthlyPaise)
              .orElse(null);
    }
    CrmLead lead =
        new CrmLead(
            Ids.newId(),
            pharmacyName.trim(),
            contactName.trim(),
            phone.trim(),
            blankToNull(email),
            source,
            LeadStage.NEW,
            LeadStage.defaultWinProbability(LeadStage.NEW),
            mrrPaise,
            blankToNull(targetPlan == null ? null : targetPlan.trim().toUpperCase(Locale.ROOT)),
            repId,
            null,
            null,
            null,
            null,
            null,
            null,
            pharmacyId,
            now,
            now);
    leads.insert(lead);
    logActivity(lead.id(), "CREATED", null, LeadStage.NEW, null, principal, now);
    if (repId != null) {
      notifyAssigned(lead.id(), repId);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", lead.id());
    data.put("pharmacy_name", lead.pharmacyName());
    data.put("stage", lead.stage());
    data.put("win_probability", lead.winProbability());
    data.put("created_at", lead.createdAt());
    return data;
  }

  @Override
  @Transactional
  public void ensureMarketplaceLead(
      UUID pharmacyId, String pharmacyName, String contactName, String phone, String email) {
    if (pharmacyId == null) {
      return;
    }
    if (phone == null || phone.isBlank()) {
      return;
    }
    if (leads.existsOpenByPharmacyId(pharmacyId, null)) {
      return;
    }
    if (leads.existsOpenByPhone(phone, null)) {
      return;
    }
    Instant now = clock.instant();
    UUID repId = leads.nextRoundRobinRepId().orElse(null);
    Long mrrPaise = plans.findPlanByName("STARTER").map(SaasPlan::priceMonthlyPaise).orElse(null);
    CrmLead lead =
        new CrmLead(
            Ids.newId(),
            pharmacyName == null || pharmacyName.isBlank() ? "Pharmacy" : pharmacyName.trim(),
            contactName == null || contactName.isBlank() ? "Owner" : contactName.trim(),
            phone.trim(),
            blankToNull(email),
            LeadSource.MARKETPLACE,
            LeadStage.CONTACTED,
            LeadStage.defaultWinProbability(LeadStage.CONTACTED),
            mrrPaise,
            "STARTER",
            repId,
            null,
            null,
            null,
            null,
            null,
            null,
            pharmacyId,
            now,
            now);
    leads.insert(lead);
    logActivity(
        lead.id(), "CREATED", null, LeadStage.CONTACTED, "Marketplace registration", null, now);
    if (repId != null) {
      notifyAssigned(lead.id(), repId);
    }
  }

  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireAdminRead(principal);
    CrmLead lead = requireLead(id);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", lead.id());
    data.put("pharmacy_name", lead.pharmacyName());
    data.put("contact_name", lead.contactName());
    data.put("phone", lead.phone());
    data.put("email", lead.email());
    data.put("source", lead.source());
    data.put("stage", lead.stage());
    data.put("win_probability", lead.winProbability());
    data.put(
        "estimated_mrr_rs",
        lead.estimatedMrrPaise() == null ? null : CrmMoney.paiseToRupees(lead.estimatedMrrPaise()));
    data.put("target_plan", lead.targetPlan());
    if (lead.assignedRepId() != null) {
      Map<String, Object> rep = new LinkedHashMap<>();
      rep.put("id", lead.assignedRepId());
      rep.put("name", leads.findRepName(lead.assignedRepId()).orElse("Rep"));
      data.put("assigned_rep", rep);
    } else {
      data.put("assigned_rep", null);
    }
    data.put("next_best_action", nextBestAction(lead));
    List<Map<String, Object>> timeline = new ArrayList<>();
    for (CrmLeadActivity a : leads.listActivities(lead.id())) {
      Map<String, Object> ev = new LinkedHashMap<>();
      ev.put("event", a.event());
      if (a.stageFrom() != null) {
        ev.put("from", a.stageFrom());
      }
      if (a.stageTo() != null) {
        ev.put("to", a.stageTo());
      }
      ev.put("at", a.createdAt());
      ev.put("actor", a.actorName() == null ? "SYSTEM" : a.actorName());
      if (a.notes() != null) {
        ev.put("notes", a.notes());
      }
      timeline.add(ev);
    }
    data.put("activity_timeline", timeline);
    data.put("notes", lead.notes());
    data.put("lost_reason", lead.lostReason());
    data.put("sales_cycle_days", lead.salesCycleDays());
    data.put("pharmacy_id", lead.pharmacyId());
    data.put("linked_account_id", lead.linkedAccountId());
    data.put("created_at", lead.createdAt());
    data.put("updated_at", lead.updatedAt());
    return data;
  }

  @Transactional
  public Map<String, Object> update(
      MedmatePrincipal principal,
      UUID id,
      UUID assignedRepId,
      BigDecimal estimatedMrrRs,
      Integer winProbability,
      String notes,
      boolean assignedRepPresent,
      boolean estimatedPresent,
      boolean winProbPresent,
      boolean notesPresent) {
    requireAdminWrite(principal);
    Instant now = clock.instant();
    CrmLead lead = requireLead(id);
    if (LeadStage.WON.equals(lead.stage())) {
      throw new AppException("LEAD_ALREADY_WON", "Lead is already won", 400);
    }
    if (LeadStage.LOST.equals(lead.stage())) {
      throw new AppException("LEAD_ALREADY_LOST", "Lead is already lost", 400);
    }
    UUID newRep = lead.assignedRepId();
    if (assignedRepPresent) {
      newRep = assignedRepId == null ? null : resolveRep(assignedRepId);
    }
    Long mrr = lead.estimatedMrrPaise();
    if (estimatedPresent) {
      mrr = estimatedMrrRs == null ? null : CrmMoney.rupeesToPaise(estimatedMrrRs);
    }
    int win = lead.winProbability();
    if (winProbPresent) {
      if (winProbability == null || winProbability < 0 || winProbability > 100) {
        throw new AppException("VALIDATION_ERROR", "win_probability must be 0-100", 422);
      }
      win = winProbability;
    }
    String newNotes = notesPresent ? notes : lead.notes();
    boolean reassigned =
        assignedRepPresent && !java.util.Objects.equals(newRep, lead.assignedRepId());
    CrmLead updated =
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            lead.stage(),
            win,
            mrr,
            lead.targetPlan(),
            newRep,
            newNotes,
            lead.lostReason(),
            lead.wonAt(),
            lead.lostAt(),
            lead.salesCycleDays(),
            lead.linkedAccountId(),
            lead.pharmacyId(),
            lead.createdAt(),
            now);
    leads.update(updated);
    if (notesPresent && notes != null && !notes.isBlank()) {
      logActivity(lead.id(), "NOTE", null, null, notes, principal, now);
    }
    if (reassigned && newRep != null) {
      logActivity(lead.id(), "ASSIGNED", null, null, null, principal, now);
      notifyAssigned(lead.id(), newRep);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("updated_at", updated.updatedAt());
    return data;
  }

  @Transactional
  public Map<String, Object> advance(MedmatePrincipal principal, UUID id, String notes) {
    requireAdminWrite(principal);
    Instant now = clock.instant();
    CrmLead lead = requireLead(id);
    if (LeadStage.WON.equals(lead.stage())) {
      throw new AppException("LEAD_ALREADY_WON", "Cannot advance a WON lead", 400);
    }
    if (LeadStage.LOST.equals(lead.stage())) {
      throw new AppException("LEAD_ALREADY_LOST", "Cannot advance a LOST lead", 400);
    }
    String next = LeadStage.nextOpen(lead.stage());
    if (next == null) {
      throw new AppException("VALIDATION_ERROR", "Use mark-won to convert a TRIAL lead", 400);
    }
    String previous = lead.stage();
    int win = LeadStage.defaultWinProbability(next);
    CrmLead updated =
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            next,
            win,
            lead.estimatedMrrPaise(),
            lead.targetPlan(),
            lead.assignedRepId(),
            lead.notes(),
            lead.lostReason(),
            lead.wonAt(),
            lead.lostAt(),
            lead.salesCycleDays(),
            lead.linkedAccountId(),
            lead.pharmacyId(),
            lead.createdAt(),
            now);
    leads.update(updated);
    logActivity(lead.id(), "STAGE_CHANGE", previous, next, notes, principal, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("previous_stage", previous);
    data.put("new_stage", next);
    data.put("win_probability", win);
    data.put("advanced_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> markWon(
      MedmatePrincipal principal, UUID id, UUID planId, String billingCycle) {
    requireAdminWrite(principal);
    Instant now = clock.instant();
    CrmLead lead = requireLead(id);
    if (LeadStage.WON.equals(lead.stage())) {
      throw new AppException("LEAD_ALREADY_WON", "Lead is already won", 400);
    }
    if (LeadStage.LOST.equals(lead.stage())) {
      throw new AppException("LEAD_ALREADY_LOST", "Cannot mark a lost lead as won", 400);
    }
    if (planId == null) {
      throw new AppException("VALIDATION_ERROR", "plan_id required", 400);
    }
    if (lead.pharmacyId() == null) {
      throw new AppException(
          "VALIDATION_ERROR", "pharmacy_id required on lead for conversion", 422);
    }
    SaasPlan plan =
        plans
            .findPlanById(planId)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    Map<String, Object> subResult =
        subscriptions.subscribeOrUpgradeForPharmacy(
            lead.pharmacyId(),
            planId,
            billingCycle == null ? "MONTHLY" : billingCycle,
            "lead-won:" + lead.id());
    UUID subscriptionId = (UUID) subResult.get("subscription_id");
    UUID accountId = plans.findAccountByPharmacyId(lead.pharmacyId()).map(a -> a.id()).orElse(null);
    int cycleDays = (int) ChronoUnit.DAYS.between(lead.createdAt(), now);
    if (cycleDays < 0) {
      cycleDays = 0;
    }
    String previous = lead.stage();
    CrmLead updated =
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            LeadStage.WON,
            LeadStage.defaultWinProbability(LeadStage.WON),
            lead.estimatedMrrPaise(),
            plan.name(),
            lead.assignedRepId(),
            lead.notes(),
            null,
            now,
            null,
            cycleDays,
            accountId,
            lead.pharmacyId(),
            lead.createdAt(),
            now);
    leads.update(updated);
    logActivity(lead.id(), "STAGE_CHANGE", previous, LeadStage.WON, "Marked won", principal, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("stage", LeadStage.WON);
    data.put("subscription_created", true);
    data.put("subscription_id", subscriptionId);
    data.put("sales_cycle_days", cycleDays);
    data.put("won_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> markLost(
      MedmatePrincipal principal, UUID id, String lostReasonRaw, String notes) {
    requireAdminWrite(principal);
    Instant now = clock.instant();
    CrmLead lead = requireLead(id);
    if (LeadStage.WON.equals(lead.stage())) {
      throw new AppException("LEAD_ALREADY_WON", "Cannot mark a won lead as lost", 400);
    }
    if (LeadStage.LOST.equals(lead.stage())) {
      throw new AppException("LEAD_ALREADY_LOST", "Lead is already lost", 400);
    }
    String reason = LostReason.requireValid(lostReasonRaw);
    String previous = lead.stage();
    String mergedNotes =
        notes == null || notes.isBlank()
            ? lead.notes()
            : (lead.notes() == null ? notes : lead.notes() + "\n" + notes);
    CrmLead updated =
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            LeadStage.LOST,
            LeadStage.defaultWinProbability(LeadStage.LOST),
            lead.estimatedMrrPaise(),
            lead.targetPlan(),
            lead.assignedRepId(),
            mergedNotes,
            reason,
            null,
            now,
            null,
            lead.linkedAccountId(),
            lead.pharmacyId(),
            lead.createdAt(),
            now);
    leads.update(updated);
    logActivity(lead.id(), "STAGE_CHANGE", previous, LeadStage.LOST, notes, principal, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("stage", LeadStage.LOST);
    data.put("lost_reason", reason);
    data.put("lost_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> reopen(MedmatePrincipal principal, UUID id) {
    requireAdminWrite(principal);
    Instant now = clock.instant();
    CrmLead lead = requireLead(id);
    if (!LeadStage.LOST.equals(lead.stage())) {
      throw new AppException("VALIDATION_ERROR", "Only LOST leads can be reopened", 400);
    }
    if (leads.existsOpenByPhone(lead.phone(), lead.id())) {
      throw new AppException("LEAD_ALREADY_EXISTS", "Pharmacy already has an open lead", 409);
    }
    CrmLead updated =
        new CrmLead(
            lead.id(),
            lead.pharmacyName(),
            lead.contactName(),
            lead.phone(),
            lead.email(),
            lead.source(),
            LeadStage.CONTACTED,
            LeadStage.defaultWinProbability(LeadStage.CONTACTED),
            lead.estimatedMrrPaise(),
            lead.targetPlan(),
            lead.assignedRepId(),
            lead.notes(),
            null,
            null,
            null,
            null,
            lead.linkedAccountId(),
            lead.pharmacyId(),
            lead.createdAt(),
            now);
    leads.update(updated);
    logActivity(
        lead.id(), "STAGE_CHANGE", LeadStage.LOST, LeadStage.CONTACTED, "Reopened", principal, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("stage", LeadStage.CONTACTED);
    data.put("win_probability", updated.winProbability());
    data.put("reopened_at", now);
    return data;
  }

  private Map<String, Object> toListItem(CrmLead lead) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", lead.id());
    m.put("pharmacy_name", lead.pharmacyName());
    m.put("contact_name", lead.contactName());
    m.put("phone", lead.phone());
    m.put("stage", lead.stage());
    m.put("win_probability", lead.winProbability());
    m.put(
        "estimated_mrr_rs",
        lead.estimatedMrrPaise() == null ? null : CrmMoney.paiseToRupees(lead.estimatedMrrPaise()));
    m.put("source", lead.source());
    m.put("assigned_rep_id", lead.assignedRepId());
    m.put(
        "assigned_rep_name",
        lead.assignedRepId() == null ? null : leads.findRepName(lead.assignedRepId()).orElse(null));
    m.put("created_at", lead.createdAt());
    return m;
  }

  private String nextBestAction(CrmLead lead) {
    long days = Math.max(0L, ChronoUnit.DAYS.between(lead.updatedAt(), clock.instant()));
    return switch (lead.stage()) {
      case LeadStage.NEW -> "Make first contact.";
      case LeadStage.CONTACTED ->
          days >= 3 ? "Schedule a demo." : "Follow up with a call or email.";
      case LeadStage.DEMO ->
          days >= 2 ? "Follow up on demo feedback; offer 14-day trial." : "Complete product demo.";
      case LeadStage.TRIAL -> days >= 7 ? "Send trial-to-paid nudge." : "Support trial onboarding.";
      case LeadStage.WON -> "Celebrate and hand off to onboarding.";
      case LeadStage.LOST -> "Consider reopen if timing changes.";
      default -> "Review lead.";
    };
  }

  private void logActivity(
      UUID leadId,
      String event,
      String from,
      String to,
      String notes,
      MedmatePrincipal principal,
      Instant at) {
    UUID actorId = principal == null ? null : principal.subject();
    String actorName =
        principal == null
            ? "SYSTEM"
            : leads.findRepName(principal.subject()).orElse(principal.role().name());
    leads.insertActivity(
        new CrmLeadActivity(Ids.newId(), leadId, event, from, to, notes, actorId, actorName, at));
  }

  private void notifyAssigned(UUID leadId, UUID repId) {
    outbox.publish(
        "crm.lead.assigned",
        leadId,
        Map.of("lead_id", leadId.toString(), "assigned_rep_id", repId.toString()));
  }

  private UUID resolveRep(UUID assignedRepId) {
    if (assignedRepId == null) {
      return null;
    }
    return leads
        .findActiveRep(assignedRepId)
        .map(SaasLeadStore.RepRef::id)
        .orElseThrow(() -> new AppException("INVALID_REP", "assigned_rep_id not found", 422));
  }

  private CrmLead requireLead(UUID id) {
    return leads
        .findById(id)
        .orElseThrow(() -> new AppException("LEAD_NOT_FOUND", "Lead not found", 404));
  }

  private static void validateCreateFields(String pharmacyName, String contactName, String phone) {
    if (pharmacyName == null || pharmacyName.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "pharmacy_name required", 400);
    }
    if (contactName == null || contactName.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "contact_name required", 400);
    }
    if (phone == null || phone.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "phone required", 400);
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static void requireAdminRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
    AuthRole r = principal.role();
    if (r != AuthRole.ADMIN_SUPER
        && r != AuthRole.ADMIN_FINANCE
        && r != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
  }

  private static void requireAdminWrite(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
    AuthRole r = principal.role();
    if (r != AuthRole.ADMIN_SUPER && r != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Admin operations access required", 403);
    }
  }
}
