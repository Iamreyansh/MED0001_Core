package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmSubscriptionOutboxPort;
import com.nammamedmate.crm.application.port.out.InvoiceCheckoutPort;
import com.nammamedmate.crm.application.port.out.InvoiceIssuingPort;
import com.nammamedmate.crm.application.port.out.PharmacyPlanSyncPort;
import com.nammamedmate.crm.application.port.out.SaasInvoicePdfPort;
import com.nammamedmate.crm.application.port.out.SaasInvoiceStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.application.port.out.SaasSubscriptionStore;
import com.nammamedmate.crm.domain.BillingCycle;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.InvoiceLineItemType;
import com.nammamedmate.crm.domain.InvoiceStatus;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.SaasGst;
import com.nammamedmate.crm.domain.SaasInvoice;
import com.nammamedmate.crm.domain.SaasInvoiceLineItem;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.crm.domain.SaasSubscription;
import com.nammamedmate.crm.domain.SubscriptionStatus;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaasBillingServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);

  @Mock SaasInvoiceStore invoices;
  @Mock SaasPlanStore plans;
  @Mock SaasSubscriptionStore subs;
  @Mock SaasInvoicePdfPort pdfs;
  @Mock InvoiceCheckoutPort checkout;
  @Mock PharmacyPlanSyncPort planSync;

  List<Map<String, Object>> outboxEvents;
  SaasBillingService service;
  UUID accountId;
  UUID subId;
  UUID pharmacyId;
  MedmatePrincipal admin;
  MedmatePrincipal finance;
  MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    outboxEvents = new ArrayList<>();
    CrmSubscriptionOutboxPort outbox =
        (type, id, payload) -> outboxEvents.add(Map.of("type", type, "id", id, "payload", payload));
    service =
        new SaasBillingService(
            invoices,
            plans,
            subs,
            pdfs,
            checkout,
            outbox,
            planSync,
            Clock.fixed(NOW, ZoneOffset.UTC));
    accountId = Ids.newId();
    subId = Ids.newId();
    pharmacyId = Ids.newId();
    admin = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "a");
    finance = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f");
    owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "o");
    when(invoices.nextInvoiceSeq(any())).thenReturn(1234);
    when(pdfs.signedGet(any(), any()))
        .thenReturn(
            new SaasInvoicePdfPort.SignedUrl(
                "file:///tmp/inv.pdf?expires=1", NOW.plusSeconds(3600)));
  }

  @Test
  @DisplayName("AC-001 Invoice on subscribe has plan line, SAC 9983, 18% GST, correct total")
  void ac001_invoiceGst() {
    UUID invId =
        service.issue(
            accountId,
            subId,
            PlanNames.STARTER,
            TODAY,
            TODAY.plusMonths(1),
            TODAY,
            List.of(
                new InvoiceIssuingPort.LineDraft(
                    "STARTER Plan - Monthly", 69900, InvoiceLineItemType.PLAN)),
            InvoiceStatus.PAID,
            NOW,
            "UPI",
            "ref");
    ArgumentCaptor<SaasInvoice> cap = ArgumentCaptor.forClass(SaasInvoice.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<SaasInvoiceLineItem>> linesCap = ArgumentCaptor.forClass(List.class);
    verify(invoices).insert(cap.capture(), linesCap.capture());
    SaasInvoice inv = cap.getValue();
    assertThat(inv.id()).isEqualTo(invId);
    assertThat(inv.invoiceNumber()).isEqualTo("NMM-INV-2026-07-001234");
    assertThat(inv.subtotalPaise()).isEqualTo(69900);
    assertThat(inv.gstAmountPaise()).isEqualTo(SaasGst.gstPaise(69900));
    assertThat(inv.totalAmountPaise()).isEqualTo(69900 + SaasGst.gstPaise(69900));
    assertThat(inv.gstRatePct()).isEqualByComparingTo(SaasGst.RATE_PCT);
    assertThat(linesCap.getValue().getFirst().sacCode()).isEqualTo(SaasGst.SAC_CODE);
  }

  @Test
  @DisplayName("AC-002 Admin chips collected/due/overdue match status aggregates")
  void ac002_chips() {
    when(invoices.chips(any(), any()))
        .thenReturn(
            new SaasInvoiceStore.BillingChips(
                42864000, 6234000, 1890000, new BigDecimal("87.4"), new BigDecimal("4.2"), 28));
    when(invoices.collectedByPlan(any(), any()))
        .thenReturn(List.of(new SaasInvoiceStore.PlanCollected(PlanNames.STARTER, 20100000)));
    when(invoices.listAdmin(any())).thenReturn(List.of());
    when(invoices.countAdmin(any())).thenReturn(0L);

    SaasBillingService.PagedResult result =
        service.listAdmin(admin, null, null, null, null, null, 1, 20);

    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) result.data().get("chips");
    assertThat(chips.get("collected_rs").toString()).isEqualTo("428640.00");
    assertThat(chips.get("due_rs").toString()).isEqualTo("62340.00");
    assertThat(chips.get("overdue_rs").toString()).isEqualTo("18900.00");
    assertThat(chips.get("collection_rate_pct")).isEqualTo(new BigDecimal("87.4"));
  }

  @Test
  @DisplayName("AC-003 Reminder on PAID invoice returns INVOICE_ALREADY_PAID")
  void ac003_reminderPaid() {
    when(invoices.findById(any())).thenReturn(Optional.of(invoice(InvoiceStatus.PAID, TODAY)));
    assertThatThrownBy(() -> service.sendReminder(admin, Ids.newId()))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_ALREADY_PAID");
  }

  @Test
  @DisplayName("AC-004 Mark paid NEFT → PAID and subscription ACTIVE")
  void ac004_markPaid() {
    SaasInvoice due = invoice(InvoiceStatus.DUE, TODAY);
    when(invoices.findById(due.id())).thenReturn(Optional.of(due));
    when(invoices.findByMarkPaidIdempotencyKey("k1")).thenReturn(Optional.empty());
    when(subs.findById(subId))
        .thenReturn(
            Optional.of(
                new SaasSubscription(
                    subId,
                    accountId,
                    UUID.fromString("a1000000-0000-4000-8000-000000000002"),
                    null,
                    SubscriptionStatus.PAST_DUE,
                    BillingCycle.MONTHLY,
                    NOW,
                    null,
                    true,
                    null,
                    null,
                    null,
                    NOW,
                    due.id(),
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    when(plans.findPlanById(any()))
        .thenReturn(
            Optional.of(
                new SaasPlan(
                    UUID.fromString("a1000000-0000-4000-8000-000000000002"),
                    PlanNames.STARTER,
                    69900,
                    2,
                    500,
                    true,
                    false,
                    NOW)));
    when(subs.findPharmacyId(accountId)).thenReturn(Optional.of(pharmacyId));

    Map<String, Object> data = service.markPaid(finance, due.id(), TODAY, "NEFT", "NEFT-1", "k1");

    assertThat(data)
        .containsEntry("status", InvoiceStatus.PAID)
        .containsEntry("subscription_status_updated_to", SubscriptionStatus.ACTIVE);
    ArgumentCaptor<SaasSubscription> cap = ArgumentCaptor.forClass(SaasSubscription.class);
    verify(subs).update(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(SubscriptionStatus.ACTIVE);
    verify(planSync).syncPlan(pharmacyId, PlanNames.STARTER);
  }

  @Test
  @DisplayName("AC-005 DSO is average (paid_at − due_date) days for PAID invoices")
  void ac005_dso() {
    when(invoices.chips(any(), any()))
        .thenReturn(
            new SaasInvoiceStore.BillingChips(
                100, 0, 0, new BigDecimal("100.0"), new BigDecimal("4.2"), 0));
    when(invoices.collectedByPlan(any(), any())).thenReturn(List.of());
    when(invoices.listAdmin(any())).thenReturn(List.of());
    when(invoices.countAdmin(any())).thenReturn(0L);

    SaasBillingService.PagedResult result =
        service.listAdmin(admin, null, null, null, null, null, null, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) result.data().get("chips");
    assertThat(chips.get("dso_days")).isEqualTo(new BigDecimal("4.2"));
  }

  @Test
  @DisplayName("AC-006 Pharmacy owner gets signed PDF download_url")
  void ac006_pdfDownload() {
    SaasInvoice paid = invoice(InvoiceStatus.PAID, TODAY);
    paid =
        new SaasInvoice(
            paid.id(),
            paid.invoiceNumber(),
            accountId,
            subId,
            paid.planName(),
            paid.billingPeriodFrom(),
            paid.billingPeriodTo(),
            paid.subtotalPaise(),
            paid.gstRatePct(),
            paid.gstAmountPaise(),
            paid.totalAmountPaise(),
            paid.status(),
            paid.dueAt(),
            paid.paidAt(),
            paid.paymentMode(),
            paid.referenceNumber(),
            paid.markedPaidBy(),
            paid.dunningStep(),
            paid.waiveReason(),
            "NMM-INV-2026-07-001234.pdf",
            null,
            null,
            null,
            null,
            paid.createdAt(),
            paid.updatedAt());
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(invoices.findById(paid.id())).thenReturn(Optional.of(paid));
    when(invoices.listLineItems(paid.id())).thenReturn(List.of());

    Map<String, Object> data = service.getPharmacy(owner, paid.id());
    assertThat(data.get("download_url").toString()).contains("file://");
    assertThat(data).containsKey("download_expires_at");
  }

  @Test
  @DisplayName("AC-007 Dunning steps advance on Day 3/7/10/14")
  void ac007_dunningSteps() {
    SaasInvoice overdue = invoice(InvoiceStatus.OVERDUE, TODAY.minusDays(3));
    overdue =
        new SaasInvoice(
            overdue.id(),
            overdue.invoiceNumber(),
            accountId,
            subId,
            overdue.planName(),
            overdue.billingPeriodFrom(),
            overdue.billingPeriodTo(),
            overdue.subtotalPaise(),
            overdue.gstRatePct(),
            overdue.gstAmountPaise(),
            overdue.totalAmountPaise(),
            InvoiceStatus.OVERDUE,
            TODAY.minusDays(3),
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(invoices.findOpenPastDue(any())).thenReturn(List.of());
    when(invoices.findForDunning(any())).thenReturn(List.of(overdue));

    service.processDunningJobs();

    ArgumentCaptor<SaasInvoice> cap = ArgumentCaptor.forClass(SaasInvoice.class);
    verify(invoices).update(cap.capture());
    assertThat(cap.getValue().dunningStep()).isEqualTo(1);
    assertThat(cap.getValue().status()).isEqualTo(InvoiceStatus.DUNNING);
    assertThat(outboxEvents).anyMatch(e -> "crm.invoice.dunning_step".equals(e.get("type")));
    assertThat(SaasBillingService.dunningStepForDays(0)).isEqualTo(0);
    assertThat(SaasBillingService.dunningStepForDays(3)).isEqualTo(1);
    assertThat(SaasBillingService.dunningStepForDays(7)).isEqualTo(2);
    assertThat(SaasBillingService.dunningStepForDays(10)).isEqualTo(3);
    assertThat(SaasBillingService.dunningStepForDays(14)).isEqualTo(4);
  }

  @Test
  @DisplayName("AC-008 Day 14 dunning expires subscription and locks premium")
  void ac008_day14Expire() {
    SaasInvoice dunning =
        new SaasInvoice(
            Ids.newId(),
            "NMM-INV-2026-07-000001",
            accountId,
            subId,
            PlanNames.STARTER,
            TODAY.minusDays(30),
            TODAY,
            69900,
            SaasGst.RATE_PCT,
            SaasGst.gstPaise(69900),
            SaasGst.totalWithGstPaise(69900),
            InvoiceStatus.DUNNING,
            TODAY.minusDays(14),
            null,
            null,
            null,
            null,
            3,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(invoices.findOpenPastDue(any())).thenReturn(List.of());
    when(invoices.findForDunning(any())).thenReturn(List.of(dunning));
    when(subs.findById(subId))
        .thenReturn(
            Optional.of(
                new SaasSubscription(
                    subId,
                    accountId,
                    UUID.fromString("a1000000-0000-4000-8000-000000000002"),
                    null,
                    SubscriptionStatus.PAST_DUE,
                    BillingCycle.MONTHLY,
                    NOW,
                    null,
                    true,
                    null,
                    null,
                    null,
                    NOW,
                    dunning.id(),
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    when(plans.findPlanByName(PlanNames.FREE))
        .thenReturn(
            Optional.of(
                new SaasPlan(
                    UUID.fromString("a1000000-0000-4000-8000-000000000001"),
                    PlanNames.FREE,
                    0,
                    1,
                    100,
                    true,
                    false,
                    NOW)));
    when(subs.findPharmacyId(accountId)).thenReturn(Optional.of(pharmacyId));

    service.processDunningJobs();

    ArgumentCaptor<SaasSubscription> cap = ArgumentCaptor.forClass(SaasSubscription.class);
    verify(subs).update(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(SubscriptionStatus.EXPIRED);
    verify(planSync).syncPlan(pharmacyId, PlanNames.FREE);
    assertThat(outboxEvents).anyMatch(e -> "crm.subscription.expired".equals(e.get("type")));
  }

  @Test
  @DisplayName("AC-009 Collection rate = PAID / (PAID + OVERDUE) × 100")
  void ac009_collectionRate() {
    // store computes; service surfaces chip value
    when(invoices.chips(any(), any()))
        .thenReturn(
            new SaasInvoiceStore.BillingChips(0, 0, 0, new BigDecimal("87.4"), BigDecimal.ZERO, 0));
    when(invoices.collectedByPlan(any(), any())).thenReturn(List.of());
    when(invoices.listAdmin(any())).thenReturn(List.of());
    when(invoices.countAdmin(any())).thenReturn(0L);
    SaasBillingService.PagedResult result =
        service.listAdmin(admin, null, null, null, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) result.data().get("chips");
    assertThat(chips.get("collection_rate_pct")).isEqualTo(new BigDecimal("87.4"));
  }

  @Test
  @DisplayName("AC-010 Waived invoice excluded from overdue and collection denominator")
  void ac010_waivedExcluded() {
    SaasInvoice due = invoice(InvoiceStatus.OVERDUE, TODAY.minusDays(2));
    when(invoices.findById(due.id())).thenReturn(Optional.of(due));
    AtomicReference<SaasInvoice> stored = new AtomicReference<>();
    org.mockito.Mockito.doAnswer(
            inv -> {
              stored.set(inv.getArgument(0));
              return null;
            })
        .when(invoices)
        .update(any());

    service.waive(due.id(), "goodwill");

    assertThat(stored.get().status()).isEqualTo(InvoiceStatus.WAIVED);
    assertThat(stored.get().waiveReason()).isEqualTo("goodwill");
    assertThat(InvoiceStatus.countsAsOverdue(InvoiceStatus.WAIVED)).isFalse();
    verify(invoices, atLeastOnce()).update(any());
  }

  private SaasInvoice invoice(String status, LocalDate due) {
    long sub = 69900;
    return new SaasInvoice(
        Ids.newId(),
        "NMM-INV-2026-07-001234",
        accountId,
        subId,
        PlanNames.STARTER,
        TODAY,
        TODAY.plusMonths(1),
        sub,
        SaasGst.RATE_PCT,
        SaasGst.gstPaise(sub),
        SaasGst.totalWithGstPaise(sub),
        status,
        due,
        InvoiceStatus.PAID.equals(status) ? NOW : null,
        InvoiceStatus.PAID.equals(status) ? "UPI" : null,
        null,
        null,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        NOW,
        NOW);
  }
}
