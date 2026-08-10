package com.nammamedmate.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.adapter.out.client.StubCampaignDispatch;
import com.nammamedmate.marketing.application.CampaignService;
import com.nammamedmate.marketing.application.port.out.CampaignDispatchPort;
import com.nammamedmate.marketing.application.port.out.CampaignStore;
import com.nammamedmate.marketing.application.port.out.CampaignTemplatePort;
import com.nammamedmate.marketing.application.port.out.NotificationDispatchPort;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import com.nammamedmate.marketing.domain.CampaignStatus;
import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.marketing.domain.SegmentType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CampaignCoverageFillTest {

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
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

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
  void fillsCreateValidationAuthAndChannels() {
    assertThatThrownBy(() -> service.create(null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.create(ops, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        " ", "PUSH", SEG, null, "s", "b", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "x".repeat(201),
                        "PUSH",
                        SEG,
                        null,
                        "s",
                        "b",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "x", null, SEG, null, "s", "b", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "x", "NOPE", SEG, null, "s", "b", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "x", "PUSH", null, null, "s", "b", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Segment deleted =
        new Segment(
            SEG,
            "D",
            null,
            SegmentType.CUSTOM,
            List.of(),
            "READY",
            0,
            null,
            null,
            null,
            null,
            NOW,
            NOW,
            NOW);
    when(segments.findById(SEG)).thenReturn(Optional.of(deleted));
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "x", "PUSH", SEG, null, "s", "b", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_SEGMENT");

    when(segments.findById(SEG)).thenReturn(Optional.of(seg()));
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "x", "PUSH", SEG, null, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        null, "PUSH", SEG, null, "s", "b", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    // WHATSAPP missing template
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "wa", "WHATSAPP", SEG, null, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHANNEL_TEMPLATE_REQUIRED");
    // SMS length edge: null and exactly 160
    when(segments.findById(SEG)).thenReturn(Optional.of(seg()));
    when(store.countSegmentMembers(SEG)).thenReturn(1);
    when(dispatch.rateCardLabel(CampaignChannel.SMS)).thenReturn("sms");
    assertThat(service.costEstimate(ops, "SMS", SEG, null).get("cost_per_recipient_paise"))
        .isEqualTo(20L);

    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    assertThat(
            service
                .create(
                    superAdmin,
                    new CampaignService.CreateCommand(
                        "Push", "PUSH", SEG, null, "Hi", "Body", " ", " ", null, 10, 20))
                .get("status"))
        .isEqualTo("DRAFT");

    when(templates.isApproved(CampaignChannel.EMAIL, TMPL)).thenReturn(true);
    // EMAIL without template ok
    assertThat(
            service
                .create(
                    ops,
                    new CampaignService.CreateCommand(
                        "Email", "EMAIL", SEG, null, "Subj", "Body", null, null, null, null, null))
                .get("status"))
        .isEqualTo("DRAFT");
  }

  @Test
  void fillsPatchLaunchPauseResumeEstimateAttributeList() {
    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.DRAFT, null)));
    when(store.timeline(CAMP)).thenReturn(List.of());
    assertThat(service.patch(ops, CAMP, null).get("status")).isEqualTo("DRAFT");

    when(segments.findById(SEG)).thenReturn(Optional.of(seg()));
    when(templates.isApproved(any(), any())).thenReturn(true);
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));

    UUID otherSeg = UUID.fromString("a0130004-0000-4000-8000-000000000003");
    when(segments.findById(otherSeg)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null,
                        "WHATSAPP",
                        otherSeg,
                        TMPL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_SEGMENT");

    Segment deletedSeg =
        new Segment(
            otherSeg,
            "X",
            null,
            SegmentType.CUSTOM,
            List.of(),
            "READY",
            0,
            null,
            null,
            null,
            null,
            NOW,
            NOW,
            NOW);
    when(segments.findById(otherSeg)).thenReturn(Optional.of(deletedSeg));
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null, null, otherSeg, null, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_SEGMENT");

    when(segments.findById(otherSeg)).thenReturn(Optional.of(seg()));
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null, null, null, null, null, null, null, null, null, null, 0)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_BUDGET");

    // DRAFT without schedule stays DRAFT when patch omits scheduled_at and existing has null
    Campaign draftNoSched =
        new Campaign(
            CAMP,
            "Monsoon",
            CampaignChannel.PUSH,
            SEG,
            null,
            "Hi",
            "Body",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            0,
            0,
            0,
            0,
            0,
            0L,
            null,
            CampaignStatus.DRAFT,
            ADMIN,
            NOW,
            NOW);
    when(store.findById(CAMP)).thenReturn(Optional.of(draftNoSched));
    assertThat(
            service
                .patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        "Still draft", null, null, null, null, null, null, null, null, null, null))
                .get("status"))
        .isEqualTo("DRAFT");

    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.DRAFT, null)));
    assertThat(
            service
                .patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        " ",
                        "SMS",
                        otherSeg,
                        TMPL,
                        "s",
                        "b",
                        "cta",
                        "https://x",
                        Instant.parse("2026-08-01T00:00:00Z"),
                        50,
                        100))
                .get("status"))
        .isEqualTo("SCHEDULED");

    // PUSH subject only / body only
    when(store.findById(CAMP)).thenReturn(Optional.of(draftNoSched));
    assertThat(
            service
                .patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null,
                        "PUSH",
                        null,
                        null,
                        "only-subject",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                .get("channel"))
        .isEqualTo("PUSH");
    assertThat(
            service
                .patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null, "PUSH", null, null, " ", "only-body", null, null, null, null, null))
                .get("channel"))
        .isEqualTo("PUSH");
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null, "PUSH", null, null, " ", " ", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(templates.isApproved(CampaignChannel.WHATSAPP, TMPL)).thenReturn(false);
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null, "WHATSAPP", null, TMPL, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_TEMPLATE");
    when(templates.isApproved(any(), any())).thenReturn(true);

    // patch PAUSED keeps status (DRAFT branch of schedule flip false)
    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.PAUSED, null)));
    assertThat(
            service
                .patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        "Paused rename",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                .get("status"))
        .isEqualTo("PAUSED");

    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CampaignService.CreateCommand(
                        "x", "  ", SEG, null, "s", "b", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findById(CAMP)).thenReturn(Optional.of(draftNoSched));
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null, "PUSH", null, null, "", "", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    // subject null + body blank against existing null subject
    Campaign pushEmpty =
        new Campaign(
            CAMP,
            "Monsoon",
            CampaignChannel.PUSH,
            SEG,
            null,
            null,
            "keep",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            0,
            0,
            0,
            0,
            0,
            0L,
            null,
            CampaignStatus.DRAFT,
            ADMIN,
            NOW,
            NOW);
    when(store.findById(CAMP)).thenReturn(Optional.of(pushEmpty));
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null, "PUSH", null, null, null, " ", null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.findById(CAMP))
        .thenReturn(
            Optional.of(
                new Campaign(
                    CAMP,
                    "Monsoon",
                    CampaignChannel.PUSH,
                    SEG,
                    null,
                    "keep",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0L,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0L,
                    null,
                    CampaignStatus.DRAFT,
                    ADMIN,
                    NOW,
                    NOW)));
    assertThatThrownBy(
            () ->
                service.patch(
                    ops,
                    CAMP,
                    new CampaignService.PatchCommand(
                        null, "PUSH", null, null, " ", null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.PAUSED, null)));
    assertThatThrownBy(() -> service.launch(ops, CAMP))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.DRAFT, null)));
    assertThatThrownBy(() -> service.pause(ops, CAMP))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.resume(ops, CAMP))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // launch with budget pause via spend check even if dispatch says not paused
    Campaign draft = sample(CampaignStatus.DRAFT, 50L);
    when(store.findById(CAMP)).thenReturn(Optional.of(draft));
    when(store.listSegmentMemberIds(SEG)).thenReturn(List.of(CUST));
    when(dispatch.dispatch(any(), anyList(), eq(85L)))
        .thenReturn(new CampaignDispatchPort.DispatchResult(1, 1, 85L, false, List.of(CUST)));
    assertThat(service.launch(ops, CAMP).get("status")).isEqualTo("PAUSED");

    assertThatThrownBy(() -> service.costEstimate(ops, "PUSH", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(segments.findById(SEG)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.costEstimate(ops, "EMAIL", SEG, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_SEGMENT");
    Segment deletedForEstimate =
        new Segment(
            SEG,
            "DORMANT",
            null,
            SegmentType.SYSTEM,
            List.of(),
            "READY",
            0,
            null,
            null,
            null,
            null,
            NOW,
            NOW,
            NOW);
    when(segments.findById(SEG)).thenReturn(Optional.of(deletedForEstimate));
    assertThatThrownBy(() -> service.costEstimate(ops, "EMAIL", SEG, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_SEGMENT");
    when(segments.findById(SEG)).thenReturn(Optional.of(seg()));
    when(store.countSegmentMembers(SEG)).thenReturn(3);
    when(dispatch.rateCardLabel(CampaignChannel.EMAIL)).thenReturn("email");
    when(dispatch.rateCardLabel(CampaignChannel.PUSH)).thenReturn("push");
    assertThat(service.costEstimate(ops, "EMAIL", SEG, null).get("cost_per_recipient_paise"))
        .isEqualTo(5L);
    assertThat(service.costEstimate(ops, "PUSH", SEG, 10).get("cost_per_recipient_paise"))
        .isEqualTo(1L);
    when(dispatch.rateCardLabel(CampaignChannel.SMS)).thenReturn("sms");
    assertThat(service.costEstimate(ops, "SMS", SEG, 160).get("cost_per_recipient_paise"))
        .isEqualTo(20L);

    when(store.findLatestInteraction(CUST))
        .thenReturn(Optional.of(new CampaignStore.Interaction(CAMP, NOW)));
    when(store.findById(CAMP)).thenReturn(Optional.empty());
    assertThat(service.attributeOrder(CUST, 1, NOW.plusSeconds(60))).isFalse();
    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.DRAFT, null)));
    assertThat(service.attributeOrder(CUST, 1, NOW.plusSeconds(60))).isFalse();
    assertThat(service.attributeOrder(CUST, -1, NOW)).isFalse();
    assertThat(service.attributeOrder(CUST, 1, null)).isFalse();

    when(store.findById(CAMP)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(ops, CAMP))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOT_FOUND");

    when(store.count(isNull(), isNull())).thenReturn(0L);
    when(store.list(isNull(), isNull(), eq("scheduled_at"), eq("asc"), eq(0), eq(100)))
        .thenReturn(List.of());
    assertThat(service.list(ops, " ", " ", 0, 0, "scheduled_at", "asc").meta().limit())
        .isEqualTo(20);
    assertThatThrownBy(() -> service.list(ops, "NOPE", null, 1, 5, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.count(eq(CampaignStatus.RUNNING), eq(CampaignChannel.PUSH))).thenReturn(1L);
    Campaign noSched =
        new Campaign(
            CAMP,
            "Monsoon",
            CampaignChannel.PUSH,
            SEG,
            null,
            "s",
            "b",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            0,
            0,
            0,
            0,
            0,
            0L,
            null,
            CampaignStatus.RUNNING,
            ADMIN,
            NOW,
            NOW);
    when(store.list(
            eq(CampaignStatus.RUNNING), eq(CampaignChannel.PUSH), isNull(), isNull(), eq(0), eq(5)))
        .thenReturn(List.of(noSched));
    when(segments.findById(SEG)).thenReturn(Optional.empty());
    assertThat(service.list(ops, "RUNNING", "PUSH", 1, 5, null, null).data().get("campaigns"))
        .asList()
        .isNotEmpty();

    // launch without budget pause (spend under cap)
    when(store.findById(CAMP)).thenReturn(Optional.of(sample(CampaignStatus.DRAFT, 10_000L)));
    when(store.listSegmentMemberIds(SEG)).thenReturn(List.of(CUST));
    when(dispatch.dispatch(any(), anyList(), eq(85L)))
        .thenReturn(new CampaignDispatchPort.DispatchResult(1, 1, 85L, false, List.of()));
    assertThat(service.launch(ops, CAMP).get("status")).isEqualTo("RUNNING");

    // parseChannelFilter blank via list channel "  "
    when(store.count(isNull(), isNull())).thenReturn(0L);
    when(store.list(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20))).thenReturn(List.of());
    service.list(ops, null, "  ", 1, 20, null, null);

    // stub dispatch null recipients + already over budget
    StubCampaignDispatch stub = new StubCampaignDispatch();
    Campaign over =
        new Campaign(
            CAMP,
            "x",
            CampaignChannel.PUSH,
            SEG,
            null,
            "s",
            "b",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            10L,
            10L,
            0,
            0,
            0,
            0,
            0,
            0L,
            null,
            CampaignStatus.RUNNING,
            ADMIN,
            NOW,
            NOW);
    assertThat(stub.dispatch(over, null, 1L).sentDelta()).isZero();
    assertThat(stub.dispatch(over, List.of(CUST), 1L).budgetPaused()).isTrue();
  }

  private static Segment seg() {
    return new Segment(
        SEG,
        "DORMANT",
        null,
        SegmentType.SYSTEM,
        List.of(),
        "READY",
        10,
        null,
        null,
        null,
        null,
        NOW,
        NOW,
        null);
  }

  private static Campaign sample(CampaignStatus status, Long budget) {
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
        budget,
        0L,
        0,
        0,
        0,
        0,
        0,
        0L,
        null,
        status,
        ADMIN,
        NOW,
        NOW);
  }
}
