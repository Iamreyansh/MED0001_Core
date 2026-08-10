package com.nammamedmate.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.application.port.out.CampaignDispatchPort;
import com.nammamedmate.marketing.application.port.out.CampaignStore;
import com.nammamedmate.marketing.application.port.out.CampaignTemplatePort;
import com.nammamedmate.marketing.application.port.out.NotificationDispatchPort;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import com.nammamedmate.marketing.domain.CampaignStatus;
import com.nammamedmate.marketing.domain.CampaignTimelineEvent;
import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.marketing.domain.SegmentType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID SEG = UUID.fromString("a0130004-0000-4000-8000-000000000002");
  private static final UUID TMPL = UUID.fromString("b0130003-0000-4000-8000-000000000001");
  private static final UUID CAMP = UUID.fromString("c0130003-0000-4000-8000-000000000001");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  @Mock CampaignStore store;
  @Mock SegmentStore segments;
  @Mock CampaignTemplatePort templates;
  @Mock CampaignDispatchPort dispatch;
  @Mock NotificationDispatchPort notifications;
  CampaignService service;

  private final MedmatePrincipal ops =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal finance =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new CampaignService(
            store,
            segments,
            templates,
            dispatch,
            notifications,
            Clock.fixed(NOW, ZoneOffset.UTC),
            1L,
            20L,
            5L,
            85L);
  }

  @Test
  void ac1_whatsappCreateIsDraftWithoutSchedule() {
    when(segments.findById(SEG)).thenReturn(Optional.of(dormantSegment()));
    when(templates.isApproved(CampaignChannel.WHATSAPP, TMPL)).thenReturn(true);
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> created =
        service.create(
            ops,
            new CampaignService.CreateCommand(
                "Monsoon Reactivation - Dormant Users",
                "WHATSAPP",
                SEG,
                TMPL,
                null,
                null,
                "Order Now",
                "https://app.nammamedmate.com/offers",
                null,
                12400,
                15000));

    assertThat(created.get("status")).isEqualTo("DRAFT");
    ArgumentCaptor<Campaign> cap = ArgumentCaptor.forClass(Campaign.class);
    verify(store).insert(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(CampaignStatus.DRAFT);
    verify(store).appendTimeline(any());
  }

  @Test
  void createScheduledWhenScheduledAtSet() {
    when(segments.findById(SEG)).thenReturn(Optional.of(dormantSegment()));
    when(templates.isApproved(CampaignChannel.WHATSAPP, TMPL)).thenReturn(true);
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> created =
        service.create(
            ops,
            new CampaignService.CreateCommand(
                "Sched",
                "WHATSAPP",
                SEG,
                TMPL,
                null,
                null,
                null,
                null,
                Instant.parse("2026-07-25T10:00:00Z"),
                null,
                null));
    assertThat(created.get("status")).isEqualTo("SCHEDULED");
  }

  @Test
  void ac2_costEstimateWhatsappDormant() {
    when(segments.findById(SEG)).thenReturn(Optional.of(dormantSegment()));
    when(store.countSegmentMembers(SEG)).thenReturn(12_400);
    when(dispatch.rateCardLabel(CampaignChannel.WHATSAPP))
        .thenReturn("Rs 0.85 per message (utility template)");

    Map<String, Object> est = service.costEstimate(ops, "WHATSAPP", SEG, null);
    assertThat(est.get("estimated_recipients")).isEqualTo(12_400);
    assertThat((Long) est.get("estimated_cost_paise")).isGreaterThan(0L);
    assertThat(est.get("cost_per_recipient_paise")).isEqualTo(85L);
  }

  @Test
  void ac3_launchScheduledToRunning() {
    Campaign scheduled = sample(CampaignStatus.SCHEDULED, null, 0, 0);
    when(store.findById(CAMP)).thenReturn(Optional.of(scheduled));
    when(store.listSegmentMemberIds(SEG)).thenReturn(List.of(CUST));
    when(dispatch.dispatch(any(), anyList(), eq(85L)))
        .thenReturn(new CampaignDispatchPort.DispatchResult(1, 1, 85L, false, List.of(CUST)));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> out = service.launch(ops, CAMP);
    assertThat(out.get("status")).isEqualTo("RUNNING");
    assertThat(out.get("launched_at")).isEqualTo(NOW.toString());
    assertThat(out.get("estimated_recipients")).isEqualTo(1);
    verify(store).insertInteraction(any(), eq(CAMP), eq(CUST), eq(NOW), eq("DELIVERED"));
  }

  @Test
  void ac4_budgetCapAutoPausesAndNotifies() {
    Campaign scheduled = sample(CampaignStatus.SCHEDULED, 100L, 0, 0); // budget 100 paise
    when(store.findById(CAMP)).thenReturn(Optional.of(scheduled));
    when(store.listSegmentMemberIds(SEG)).thenReturn(List.of(CUST, UUID.randomUUID()));
    when(dispatch.dispatch(any(), anyList(), eq(85L)))
        .thenReturn(new CampaignDispatchPort.DispatchResult(2, 2, 170L, true, List.of(CUST)));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> out = service.launch(ops, CAMP);
    assertThat(out.get("status")).isEqualTo("PAUSED");
    verify(notifications).notifyCampaignBudgetPaused(eq("Monsoon"), eq(CAMP));
  }

  @Test
  void ac5_attribution24hYes49hNo() {
    Instant click = NOW;
    when(store.findLatestInteraction(CUST))
        .thenReturn(Optional.of(new CampaignStore.Interaction(CAMP, click)));
    Campaign running = sample(CampaignStatus.RUNNING, null, 100, 50);
    when(store.findById(CAMP)).thenReturn(Optional.of(running));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));

    assertThat(service.attributeOrder(CUST, 10_000L, click.plusSeconds(24 * 3600))).isTrue();
    ArgumentCaptor<Campaign> cap = ArgumentCaptor.forClass(Campaign.class);
    verify(store).update(cap.capture());
    assertThat(cap.getValue().convertedCount()).isEqualTo(51);
    assertThat(cap.getValue().revenueAttributedPaise()).isEqualTo(10_100L);

    assertThat(service.attributeOrder(CUST, 10_000L, click.plusSeconds(49 * 3600))).isFalse();
    assertThat(service.attributeOrder(null, 1, NOW)).isFalse();
    when(store.findLatestInteraction(CUST)).thenReturn(Optional.empty());
    assertThat(service.attributeOrder(CUST, 1, NOW)).isFalse();
  }

  @Test
  void ac6_editRunningReturns409() {
    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.RUNNING, null, 0, 0)));
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        "x", null, null, null, null, null, null, null, null, null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CAMPAIGN_ALREADY_RUNNING");
  }

  @Test
  void ac7_smsWithoutTemplateRequired() {
    when(segments.findById(SEG)).thenReturn(Optional.of(dormantSegment()));
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "SMS blast", "SMS", SEG, null, null, null, null, null, null, null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHANNEL_TEMPLATE_REQUIRED");
  }

  @Test
  void ac8_and_ac9_detailRoiAndFunnel() {
    Campaign done =
        new Campaign(
            CAMP,
            "Monsoon",
            CampaignChannel.WHATSAPP,
            SEG,
            TMPL,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW,
            null,
            null,
            null,
            2_580_000L,
            12400,
            12185,
            4655,
            1054,
            620,
            18_600_000L,
            12400,
            CampaignStatus.COMPLETED,
            ADMIN,
            NOW,
            NOW);
    when(store.findById(CAMP)).thenReturn(Optional.of(done));
    when(store.timeline(CAMP))
        .thenReturn(
            List.of(
                new CampaignTimelineEvent(
                    UUID.randomUUID(), CAMP, "CREATED", NOW, ADMIN.toString()),
                new CampaignTimelineEvent(
                    UUID.randomUUID(), CAMP, "LAUNCHED", NOW, ADMIN.toString()),
                new CampaignTimelineEvent(UUID.randomUUID(), CAMP, "COMPLETED", NOW, "SYSTEM")));

    Map<String, Object> detail = service.get(finance, CAMP);
    @SuppressWarnings("unchecked")
    Map<String, Object> funnel = (Map<String, Object>) detail.get("funnel");
    assertThat(funnel.get("sent")).isEqualTo(12400);
    assertThat(funnel.get("delivered")).isEqualTo(12185);
    assertThat(funnel.get("opened")).isEqualTo(4655);
    assertThat(funnel.get("clicked")).isEqualTo(1054);
    assertThat(funnel.get("converted")).isEqualTo(620);
    @SuppressWarnings("unchecked")
    Map<String, Object> eco = (Map<String, Object>) detail.get("economics");
    assertThat((BigDecimal) eco.get("roi_pct")).isEqualByComparingTo(new BigDecimal("620.9"));
  }

  @Test
  void ac10_resumePaused() {
    when(store.findById(CAMP))
        .thenReturn(Optional.of(sample(CampaignStatus.PAUSED, 1000L, 100, 10)));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> out = service.resume(ops, CAMP);
    assertThat(out.get("status")).isEqualTo("RUNNING");
    assertThat(out.get("resumed_at")).isEqualTo(NOW.toString());
  }

  @Test
  void launchErrorsAndPauseAndListAndInvalidTemplateBudget() {
    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.RUNNING, null, 0, 0)));
    assertThatThrownBy(() -> service.launch(ops, CAMP))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CAMPAIGN_ALREADY_RUNNING");

    when(store.findById(CAMP))
        .thenReturn(Optional.of(sample(CampaignStatus.COMPLETED, null, 0, 0)));
    assertThatThrownBy(() -> service.launch(ops, CAMP))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CAMPAIGN_COMPLETED");
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null, null, null, null, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CAMPAIGN_COMPLETED");

    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.RUNNING, null, 0, 0)));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));
    assertThat(service.pause(ops, CAMP).get("status")).isEqualTo("PAUSED");

    when(segments.findById(SEG)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "x", "PUSH", SEG, null, "s", "b", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_SEGMENT");

    when(segments.findById(SEG)).thenReturn(Optional.of(dormantSegment()));
    when(templates.isApproved(eq(CampaignChannel.SMS), any())).thenReturn(false);
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "x", "SMS", SEG, TMPL, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_TEMPLATE");

    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "x", "PUSH", SEG, null, "s", "b", null, null, null, null, 0)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_BUDGET");

    when(store.count(any(), any())).thenReturn(1L);
    when(store.list(any(), any(), any(), any(), eq(0), eq(20)))
        .thenReturn(List.of(sample(CampaignStatus.DRAFT, null, 0, 0)));
    when(segments.findById(SEG)).thenReturn(Optional.of(dormantSegment()));
    assertThat(service.list(finance, null, null, null, null, null, null).meta().total())
        .isEqualTo(1);

    assertThatThrownBy(() -> service.costEstimate(finance, "WHATSAPP", SEG, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(store.countSegmentMembers(SEG)).thenReturn(10);
    when(dispatch.rateCardLabel(CampaignChannel.SMS)).thenReturn("sms");
    assertThat(service.costEstimate(ops, "SMS", SEG, 200).get("cost_per_recipient_paise"))
        .isEqualTo(40L);

    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.DRAFT, null, 0, 0)));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));
    when(templates.isApproved(CampaignChannel.WHATSAPP, TMPL)).thenReturn(true);
    when(store.timeline(CAMP)).thenReturn(List.of());
    assertThat(
            service
                .patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        "Renamed", null, null, null, null, null, null, null, null, null, null))
                .get("name"))
        .isEqualTo("Renamed");

    verify(notifications, never()).notifyCouponBudgetExhausted(any(), any());
  }

  private static Segment dormantSegment() {
    return new Segment(
        SEG,
        "DORMANT",
        "dormant",
        SegmentType.SYSTEM,
        List.of(),
        "READY",
        12400,
        null,
        null,
        null,
        null,
        NOW,
        NOW,
        null);
  }

  private static Campaign sample(
      CampaignStatus status, Long budgetCapPaise, long spend, int converted) {
    return new Campaign(
        CAMP,
        "Monsoon",
        CampaignChannel.WHATSAPP,
        SEG,
        TMPL,
        null,
        null,
        null,
        null,
        Instant.parse("2026-07-25T10:00:00Z"),
        null,
        null,
        null,
        null,
        budgetCapPaise,
        spend,
        0,
        0,
        0,
        0,
        converted,
        spend,
        null,
        status,
        ADMIN,
        NOW,
        NOW);
  }
}
