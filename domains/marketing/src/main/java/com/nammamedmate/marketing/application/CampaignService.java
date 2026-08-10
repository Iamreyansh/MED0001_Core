package com.nammamedmate.marketing.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.marketing.application.port.out.CampaignDispatchPort;
import com.nammamedmate.marketing.application.port.out.CampaignStore;
import com.nammamedmate.marketing.application.port.out.CampaignTemplatePort;
import com.nammamedmate.marketing.application.port.out.NotificationDispatchPort;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import com.nammamedmate.marketing.domain.CampaignStatus;
import com.nammamedmate.marketing.domain.CampaignTimelineEvent;
import com.nammamedmate.marketing.domain.MoneyFormats;
import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignService {

  private final CampaignStore store;
  private final SegmentStore segments;
  private final CampaignTemplatePort templates;
  private final CampaignDispatchPort dispatch;
  private final NotificationDispatchPort notifications;
  private final Clock clock;
  private final long ratePushPaise;
  private final long rateSmsPaise;
  private final long rateEmailPaise;
  private final long rateWhatsappPaise;

  public CampaignService(
      CampaignStore store,
      SegmentStore segments,
      CampaignTemplatePort templates,
      CampaignDispatchPort dispatch,
      NotificationDispatchPort notifications,
      Clock clock,
      @Value("${medmate.marketing.campaign.rates.push-paise:1}") long ratePushPaise,
      @Value("${medmate.marketing.campaign.rates.sms-paise:20}") long rateSmsPaise,
      @Value("${medmate.marketing.campaign.rates.email-paise:5}") long rateEmailPaise,
      @Value("${medmate.marketing.campaign.rates.whatsapp-paise:85}") long rateWhatsappPaise) {
    this.store = store;
    this.segments = segments;
    this.templates = templates;
    this.dispatch = dispatch;
    this.notifications = notifications;
    this.clock = clock;
    this.ratePushPaise = ratePushPaise;
    this.rateSmsPaise = rateSmsPaise;
    this.rateEmailPaise = rateEmailPaise;
    this.rateWhatsappPaise = rateWhatsappPaise;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {}

  public record CreateCommand(
      String name,
      String channel,
      UUID segmentId,
      UUID messageTemplateId,
      String subject,
      String body,
      String ctaLabel,
      String ctaLink,
      Instant scheduledAt,
      Number estimatedCost,
      Number budgetCap) {}

  public record PatchCommand(
      String name,
      String channel,
      UUID segmentId,
      UUID messageTemplateId,
      String subject,
      String body,
      String ctaLabel,
      String ctaLink,
      Instant scheduledAt,
      Number estimatedCost,
      Number budgetCap) {}

  @Transactional(readOnly = true)
  public PagedResult list(
      MedmatePrincipal principal,
      String status,
      String channel,
      Integer page,
      Integer limit,
      String sort,
      String order) {
    requireAdminRead(principal);
    int p = normalizePage(page);
    int lim = normalizeLimit(limit);
    CampaignStatus st = parseStatusFilter(status);
    CampaignChannel ch = parseChannelFilter(channel);
    long total = store.count(st, ch);
    List<Campaign> rows = store.list(st, ch, sort, order, (p - 1) * lim, lim);
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (Campaign c : rows) {
      items.add(toListItem(c));
    }
    return new PagedResult(Map.of("campaigns", items), PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, CreateCommand cmd) {
    requireAdminWrite(principal);
    if (cmd == null || cmd.name() == null || cmd.name().isBlank()) {
      throw new AppException("VALIDATION_ERROR", "name is required", 422);
    }
    if (cmd.name().length() > 200) {
      throw new AppException("VALIDATION_ERROR", "name max 200 chars", 422);
    }
    CampaignChannel channel = parseChannelRequired(cmd.channel());
    if (cmd.segmentId() == null) {
      throw new AppException("VALIDATION_ERROR", "segment_id is required", 422);
    }
    Segment segment =
        segments
            .findById(cmd.segmentId())
            .filter(s -> s.deletedAt() == null)
            .orElseThrow(
                () -> new AppException("INVALID_SEGMENT", "segment_id does not exist", 422));
    validateChannelContent(channel, cmd.messageTemplateId(), cmd.subject(), cmd.body());
    Long budgetPaise = null;
    if (cmd.budgetCap() != null) {
      long b = toPaise(cmd.budgetCap());
      if (b <= 0) {
        throw new AppException("INVALID_BUDGET", "budget_cap must be > 0", 422);
      }
      budgetPaise = b;
    }
    Long estimatedPaise = cmd.estimatedCost() == null ? null : toPaise(cmd.estimatedCost());
    Instant now = clock.instant();
    CampaignStatus status =
        cmd.scheduledAt() != null ? CampaignStatus.SCHEDULED : CampaignStatus.DRAFT;
    Campaign created =
        store.insert(
            new Campaign(
                Ids.newId(),
                cmd.name().trim(),
                channel,
                segment.id(),
                cmd.messageTemplateId(),
                blankToNull(cmd.subject()),
                blankToNull(cmd.body()),
                blankToNull(cmd.ctaLabel()),
                blankToNull(cmd.ctaLink()),
                cmd.scheduledAt(),
                null,
                null,
                null,
                estimatedPaise,
                budgetPaise,
                0L,
                0,
                0,
                0,
                0,
                0,
                0L,
                null,
                status,
                principal.subject(),
                now,
                now));
    store.appendTimeline(
        new CampaignTimelineEvent(
            Ids.newId(), created.id(), "CREATED", now, principal.subject().toString()));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", created.id().toString());
    data.put("name", created.name());
    data.put("status", created.status().name());
    data.put("created_at", created.createdAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> patch(MedmatePrincipal principal, UUID id, PatchCommand cmd) {
    requireAdminWrite(principal);
    Campaign existing = requireCampaign(id);
    if (existing.status() == CampaignStatus.RUNNING) {
      throw new AppException(
          "CAMPAIGN_ALREADY_RUNNING", "Campaign cannot be edited while RUNNING", 409);
    }
    if (existing.status() == CampaignStatus.COMPLETED) {
      throw new AppException("CAMPAIGN_COMPLETED", "Completed campaigns cannot be edited", 409);
    }
    if (cmd == null) {
      return toDetail(existing);
    }
    CampaignChannel channel =
        cmd.channel() != null ? parseChannelRequired(cmd.channel()) : existing.channel();
    UUID segmentId = cmd.segmentId() != null ? cmd.segmentId() : existing.segmentId();
    if (cmd.segmentId() != null) {
      segments
          .findById(segmentId)
          .filter(s -> s.deletedAt() == null)
          .orElseThrow(() -> new AppException("INVALID_SEGMENT", "segment_id does not exist", 422));
    }
    UUID templateId =
        cmd.messageTemplateId() != null ? cmd.messageTemplateId() : existing.messageTemplateId();
    String subject = cmd.subject() != null ? blankToNull(cmd.subject()) : existing.subject();
    String body = cmd.body() != null ? blankToNull(cmd.body()) : existing.body();
    validateChannelContent(channel, templateId, subject, body);
    Long budgetPaise = existing.budgetCapPaise();
    if (cmd.budgetCap() != null) {
      long b = toPaise(cmd.budgetCap());
      if (b <= 0) {
        throw new AppException("INVALID_BUDGET", "budget_cap must be > 0", 422);
      }
      budgetPaise = b;
    }
    Long estimatedPaise =
        cmd.estimatedCost() != null
            ? Long.valueOf(toPaise(cmd.estimatedCost()))
            : existing.estimatedCostPaise();
    Instant scheduledAt = cmd.scheduledAt() != null ? cmd.scheduledAt() : existing.scheduledAt();
    CampaignStatus status = existing.status();
    if (existing.status() == CampaignStatus.DRAFT && scheduledAt != null) {
      status = CampaignStatus.SCHEDULED;
    }
    Instant now = clock.instant();
    Campaign updated =
        store.update(
            new Campaign(
                existing.id(),
                cmd.name() != null && !cmd.name().isBlank() ? cmd.name().trim() : existing.name(),
                channel,
                segmentId,
                templateId,
                subject,
                body,
                cmd.ctaLabel() != null ? blankToNull(cmd.ctaLabel()) : existing.ctaLabel(),
                cmd.ctaLink() != null ? blankToNull(cmd.ctaLink()) : existing.ctaLink(),
                scheduledAt,
                existing.launchedAt(),
                existing.completedAt(),
                existing.pausedAt(),
                estimatedPaise,
                budgetPaise,
                existing.actualSpendPaise(),
                existing.sentCount(),
                existing.deliveredCount(),
                existing.openedCount(),
                existing.clickedCount(),
                existing.convertedCount(),
                existing.revenueAttributedPaise(),
                existing.audienceSnapshotCount(),
                status,
                existing.createdBy(),
                existing.createdAt(),
                now));
    return toDetail(updated);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireAdminRead(principal);
    return toDetail(requireCampaign(id));
  }

  @Transactional
  public Map<String, Object> launch(MedmatePrincipal principal, UUID id) {
    requireAdminWrite(principal);
    Campaign c = requireCampaign(id);
    if (c.status() == CampaignStatus.RUNNING) {
      throw new AppException("CAMPAIGN_ALREADY_RUNNING", "Campaign already in RUNNING state", 409);
    }
    if (c.status() == CampaignStatus.COMPLETED) {
      throw new AppException("CAMPAIGN_COMPLETED", "Cannot relaunch a completed campaign", 409);
    }
    if (c.status() == CampaignStatus.PAUSED) {
      throw new AppException("VALIDATION_ERROR", "Use resume for paused campaigns", 422);
    }
    Instant now = clock.instant();
    List<UUID> members = store.listSegmentMemberIds(c.segmentId());
    long costPer = costPerRecipientPaise(c.channel(), null);
    CampaignDispatchPort.DispatchResult result = dispatch.dispatch(c, members, costPer);
    long spend = c.actualSpendPaise() + result.spendDeltaPaise();
    CampaignStatus status = CampaignStatus.RUNNING;
    Instant pausedAt = null;
    if (result.budgetPaused() || (c.budgetCapPaise() != null && spend >= c.budgetCapPaise())) {
      status = CampaignStatus.PAUSED;
      pausedAt = now;
    }
    Campaign updated =
        store.update(
            new Campaign(
                c.id(),
                c.name(),
                c.channel(),
                c.segmentId(),
                c.messageTemplateId(),
                c.subject(),
                c.body(),
                c.ctaLabel(),
                c.ctaLink(),
                c.scheduledAt(),
                now,
                c.completedAt(),
                pausedAt,
                c.estimatedCostPaise(),
                c.budgetCapPaise(),
                spend,
                c.sentCount() + result.sentDelta(),
                c.deliveredCount() + result.deliveredDelta(),
                c.openedCount(),
                c.clickedCount(),
                c.convertedCount(),
                c.revenueAttributedPaise(),
                members.size(),
                status,
                c.createdBy(),
                c.createdAt(),
                now));
    store.appendTimeline(
        new CampaignTimelineEvent(
            Ids.newId(), c.id(), "LAUNCHED", now, principal.subject().toString()));
    Instant interactionAt = now;
    for (UUID customerId : result.deliveredCustomerIds()) {
      store.insertInteraction(Ids.newId(), c.id(), customerId, interactionAt, "DELIVERED");
    }
    if (status == CampaignStatus.PAUSED) {
      store.appendTimeline(
          new CampaignTimelineEvent(Ids.newId(), c.id(), "BUDGET_PAUSED", now, "SYSTEM"));
      notifications.notifyCampaignBudgetPaused(c.name(), c.id());
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id().toString());
    data.put("status", updated.status().name());
    data.put("launched_at", now.toString());
    data.put("estimated_recipients", members.size());
    return data;
  }

  @Transactional
  public Map<String, Object> pause(MedmatePrincipal principal, UUID id) {
    requireAdminWrite(principal);
    Campaign c = requireCampaign(id);
    if (c.status() != CampaignStatus.RUNNING) {
      throw new AppException("VALIDATION_ERROR", "Only RUNNING campaigns can be paused", 422);
    }
    Instant now = clock.instant();
    Campaign updated =
        store.update(
            new Campaign(
                c.id(),
                c.name(),
                c.channel(),
                c.segmentId(),
                c.messageTemplateId(),
                c.subject(),
                c.body(),
                c.ctaLabel(),
                c.ctaLink(),
                c.scheduledAt(),
                c.launchedAt(),
                c.completedAt(),
                now,
                c.estimatedCostPaise(),
                c.budgetCapPaise(),
                c.actualSpendPaise(),
                c.sentCount(),
                c.deliveredCount(),
                c.openedCount(),
                c.clickedCount(),
                c.convertedCount(),
                c.revenueAttributedPaise(),
                c.audienceSnapshotCount(),
                CampaignStatus.PAUSED,
                c.createdBy(),
                c.createdAt(),
                now));
    store.appendTimeline(
        new CampaignTimelineEvent(
            Ids.newId(), c.id(), "PAUSED", now, principal.subject().toString()));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id().toString());
    data.put("status", "PAUSED");
    data.put("paused_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> resume(MedmatePrincipal principal, UUID id) {
    requireAdminWrite(principal);
    Campaign c = requireCampaign(id);
    if (c.status() != CampaignStatus.PAUSED) {
      throw new AppException("VALIDATION_ERROR", "Only PAUSED campaigns can be resumed", 422);
    }
    Instant now = clock.instant();
    Campaign updated =
        store.update(
            new Campaign(
                c.id(),
                c.name(),
                c.channel(),
                c.segmentId(),
                c.messageTemplateId(),
                c.subject(),
                c.body(),
                c.ctaLabel(),
                c.ctaLink(),
                c.scheduledAt(),
                c.launchedAt(),
                c.completedAt(),
                null,
                c.estimatedCostPaise(),
                c.budgetCapPaise(),
                c.actualSpendPaise(),
                c.sentCount(),
                c.deliveredCount(),
                c.openedCount(),
                c.clickedCount(),
                c.convertedCount(),
                c.revenueAttributedPaise(),
                c.audienceSnapshotCount(),
                CampaignStatus.RUNNING,
                c.createdBy(),
                c.createdAt(),
                now));
    store.appendTimeline(
        new CampaignTimelineEvent(
            Ids.newId(), c.id(), "RESUMED", now, principal.subject().toString()));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id().toString());
    data.put("status", "RUNNING");
    data.put("resumed_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> costEstimate(
      MedmatePrincipal principal, String channel, UUID segmentId, Integer messageLength) {
    requireAdminWrite(principal);
    CampaignChannel ch = parseChannelRequired(channel);
    if (segmentId == null) {
      throw new AppException("VALIDATION_ERROR", "segment_id is required", 422);
    }
    segments
        .findById(segmentId)
        .filter(s -> s.deletedAt() == null)
        .orElseThrow(() -> new AppException("INVALID_SEGMENT", "segment_id does not exist", 422));
    int recipients = store.countSegmentMembers(segmentId);
    long per = costPerRecipientPaise(ch, messageLength);
    long total = per * (long) recipients;
    Map<String, Object> rateCard = new LinkedHashMap<>();
    rateCard.put(ch.name(), dispatch.rateCardLabel(ch));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("estimated_recipients", recipients);
    data.put("estimated_cost_paise", total);
    data.put("cost_per_recipient_paise", per);
    data.put("channel_rate_card", rateCard);
    return data;
  }

  /**
   * Attribute order GMV to the customer's latest campaign interaction within 48h. Bridge from order
   * module later.
   *
   * @return true when attributed
   */
  @Transactional
  public boolean attributeOrder(UUID customerId, long orderTotalPaise, Instant deliveredAt) {
    if (customerId == null || deliveredAt == null || orderTotalPaise < 0) {
      return false;
    }
    Optional<CampaignStore.Interaction> hit = store.findLatestInteraction(customerId);
    if (hit.isEmpty()) {
      return false;
    }
    CampaignStore.Interaction interaction = hit.get();
    if (!Campaign.isAttributable(interaction.interactedAt(), deliveredAt)) {
      return false;
    }
    Campaign c = store.findById(interaction.campaignId()).orElse(null);
    if (c == null || c.status() == CampaignStatus.DRAFT) {
      return false;
    }
    Instant now = clock.instant();
    store.update(
        new Campaign(
            c.id(),
            c.name(),
            c.channel(),
            c.segmentId(),
            c.messageTemplateId(),
            c.subject(),
            c.body(),
            c.ctaLabel(),
            c.ctaLink(),
            c.scheduledAt(),
            c.launchedAt(),
            c.completedAt(),
            c.pausedAt(),
            c.estimatedCostPaise(),
            c.budgetCapPaise(),
            c.actualSpendPaise(),
            c.sentCount(),
            c.deliveredCount(),
            c.openedCount(),
            c.clickedCount(),
            c.convertedCount() + 1,
            c.revenueAttributedPaise() + orderTotalPaise,
            c.audienceSnapshotCount(),
            c.status(),
            c.createdBy(),
            c.createdAt(),
            now));
    return true;
  }

  private void validateChannelContent(
      CampaignChannel channel, UUID templateId, String subject, String body) {
    if (channel == CampaignChannel.SMS || channel == CampaignChannel.WHATSAPP) {
      if (templateId == null) {
        throw new AppException(
            "CHANNEL_TEMPLATE_REQUIRED",
            channel.name() + " campaign requires message_template_id",
            422);
      }
      if (!templates.isApproved(channel, templateId)) {
        throw new AppException(
            "INVALID_TEMPLATE", "message_template_id not found or not approved", 422);
      }
    }
    if (channel == CampaignChannel.PUSH) {
      boolean missingSubject = subject == null ? true : subject.isBlank();
      boolean missingBody = body == null ? true : body.isBlank();
      if (missingSubject && missingBody) {
        throw new AppException("VALIDATION_ERROR", "PUSH requires subject or body", 422);
      }
    }
  }

  private long costPerRecipientPaise(CampaignChannel channel, Integer messageLength) {
    return switch (channel) {
      case PUSH -> ratePushPaise;
      case EMAIL -> rateEmailPaise;
      case WHATSAPP -> rateWhatsappPaise;
      case SMS -> {
        long base = rateSmsPaise;
        if (messageLength != null && messageLength > 160) {
          yield base * 2;
        }
        yield base;
      }
    };
  }

  private Map<String, Object> toListItem(Campaign c) {
    String segmentName =
        segments.findById(c.segmentId()).map(Segment::name).orElse(c.segmentId().toString());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", c.id().toString());
    m.put("name", c.name());
    m.put("channel", c.channel().name());
    m.put("target_segment", segmentName);
    m.put("sent_count", c.sentCount());
    m.put("open_rate", c.openRatePct());
    m.put("ctr", c.ctrPct());
    m.put("conversions", c.convertedCount());
    m.put("revenue_attributed_rs", MoneyFormats.paiseToRupees(c.revenueAttributedPaise()));
    m.put("roi_pct", c.roiPct());
    m.put("status", c.status().name());
    m.put("scheduled_at", c.scheduledAt() == null ? null : c.scheduledAt().toString());
    return m;
  }

  private Map<String, Object> toDetail(Campaign c) {
    Map<String, Object> funnel = new LinkedHashMap<>();
    funnel.put("sent", c.sentCount());
    funnel.put("delivered", c.deliveredCount());
    funnel.put("opened", c.openedCount());
    funnel.put("clicked", c.clickedCount());
    funnel.put("converted", c.convertedCount());
    Map<String, Object> economics = new LinkedHashMap<>();
    economics.put("total_cost_rs", MoneyFormats.paiseToRupees(c.actualSpendPaise()));
    economics.put("revenue_attributed_rs", MoneyFormats.paiseToRupees(c.revenueAttributedPaise()));
    economics.put("roi_pct", c.roiPct());
    List<Map<String, Object>> timeline = new ArrayList<>();
    for (CampaignTimelineEvent e : store.timeline(c.id())) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("event", e.event());
      row.put("at", e.at().toString());
      row.put("actor", e.actor());
      timeline.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", c.id().toString());
    data.put("name", c.name());
    data.put("channel", c.channel().name());
    data.put("segment_id", c.segmentId().toString());
    data.put("status", c.status().name());
    data.put("funnel", funnel);
    data.put("economics", economics);
    data.put("timeline", timeline);
    return data;
  }

  private Campaign requireCampaign(UUID id) {
    return store
        .findById(id)
        .orElseThrow(() -> new AppException("NOT_FOUND", "Campaign not found", 404));
  }

  private static CampaignStatus parseStatusFilter(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return CampaignStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "invalid status", 422);
    }
  }

  private static CampaignChannel parseChannelFilter(String channel) {
    if (channel == null || channel.isBlank()) {
      return null;
    }
    return parseChannelRequired(channel);
  }

  private static CampaignChannel parseChannelRequired(String channel) {
    if (channel == null || channel.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "channel is required", 422);
    }
    try {
      return CampaignChannel.valueOf(channel.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "invalid channel", 422);
    }
  }

  private static long toPaise(Number rupees) {
    return MoneyFormats.rupeesToPaise(BigDecimal.valueOf(rupees.doubleValue()));
  }

  private static String blankToNull(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    return s.trim();
  }

  private static int normalizePage(Integer page) {
    if (page == null || page < 1) {
      return 1;
    }
    return page;
  }

  private static int normalizeLimit(Integer limit) {
    if (limit == null || limit < 1) {
      return 20;
    }
    return Math.min(limit, 100);
  }

  private static void requireAdminRead(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_FINANCE);
  }

  private static void requireAdminWrite(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  }

  private static void requireRole(MedmatePrincipal principal, AuthRole... allowed) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    for (AuthRole role : allowed) {
      if (principal.role() == role) {
        return;
      }
    }
    throw new AppException("FORBIDDEN", "Insufficient role", 403);
  }
}
