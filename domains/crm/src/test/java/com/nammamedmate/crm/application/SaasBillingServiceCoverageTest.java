package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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
import com.nammamedmate.crm.domain.AccountAddon;
import com.nammamedmate.crm.domain.BillingCycle;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.InvoiceLineItemType;
import com.nammamedmate.crm.domain.InvoiceStatus;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.SaasAddon;
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
import java.util.List;
import java.util.Map;
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
class SaasBillingServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);

  @Mock SaasInvoiceStore invoices;
  @Mock SaasPlanStore plans;
  @Mock SaasSubscriptionStore subs;
  @Mock SaasInvoicePdfPort pdfs;
  @Mock InvoiceCheckoutPort checkout;
  @Mock CrmSubscriptionOutboxPort outbox;
  @Mock PharmacyPlanSyncPort planSync;

  SaasBillingService service;
  UUID accountId;
  UUID subId;
  UUID pharmacyId;
  MedmatePrincipal admin;
  MedmatePrincipal ops;
  MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
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
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
    owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "p");
    lenient().when(invoices.nextInvoiceSeq(any())).thenReturn(1);
    lenient()
        .when(pdfs.signedGet(any(), any()))
        .thenReturn(new SaasInvoicePdfPort.SignedUrl("file:///x.pdf", NOW.plusSeconds(60)));
  }

  @Test
  void authAndValidationBranches() {
    assertThatThrownBy(() -> service.listAdmin(null, null, null, null, null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.listAdmin(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "x"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.markPaid(ops, Ids.newId(), TODAY, "NEFT", "r", "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.markPaid(admin, Ids.newId(), TODAY, "NEFT", "r", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.markPaid(admin, Ids.newId(), TODAY, "NEFT", "r", "x".repeat(129)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listPharmacy(null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.issue(
                    accountId, subId, "S", TODAY, TODAY, TODAY, List.of(), "DUE", null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.waive(Ids.newId(), " "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_NOT_FOUND");
  }

  @Test
  void listDetailReminderPayAndIdempotency() {
    SaasInvoice due = inv(InvoiceStatus.DUE, null);
    when(invoices.chips(any(), any()))
        .thenReturn(
            new SaasInvoiceStore.BillingChips(0, 100, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0));
    when(invoices.collectedByPlan(any(), any())).thenReturn(List.of());
    when(invoices.listAdmin(any())).thenReturn(List.of(due));
    when(invoices.countAdmin(any())).thenReturn(1L);
    when(invoices.pharmacyProfile(accountId))
        .thenReturn(
            new SaasInvoiceStore.PharmacyBillingProfile(
                "Apollo", "HSR", "29ABCDE1234F1Z5", pharmacyId));
    when(invoices.findById(due.id())).thenReturn(Optional.of(due));
    when(invoices.listLineItems(due.id()))
        .thenReturn(
            List.of(
                new SaasInvoiceLineItem(
                    Ids.newId(),
                    due.id(),
                    "STARTER Plan - Monthly",
                    SaasGst.SAC_CODE,
                    69900,
                    InvoiceLineItemType.PLAN,
                    NOW)));
    UUID addonId = Ids.newId();
    UUID otherAddon = Ids.newId();
    when(plans.listActiveAddons())
        .thenReturn(
            List.of(
                new SaasAddon(addonId, "E_INVOICE", 19900, "e", true),
                new SaasAddon(otherAddon, "X", 100, "x", true)));
    when(plans.findActiveAccountAddon(accountId, addonId))
        .thenReturn(Optional.of(new AccountAddon(accountId, addonId, NOW.minusSeconds(10), null)));
    when(plans.findActiveAccountAddon(accountId, otherAddon)).thenReturn(Optional.empty());

    assertThat(
            service
                .listAdmin(ops, "DUE", PlanNames.STARTER, accountId, TODAY, TODAY, 0, 0)
                .meta()
                .total())
        .isEqualTo(1);
    Map<String, Object> detail = service.getAdmin(admin, due.id());
    assertThat(detail).containsKeys("line_items", "next_cycle_addons", "gstin");

    Map<String, Object> reminder = service.sendReminder(admin, due.id());
    assertThat(reminder.get("reminder_sent_via")).isEqualTo(List.of("EMAIL", "WHATSAPP"));

    UUID missing = Ids.newId();
    when(invoices.findById(missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.sendReminder(admin, missing))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_NOT_FOUND");

    SaasInvoice paid = inv(InvoiceStatus.PAID, NOW);
    when(invoices.findById(paid.id())).thenReturn(Optional.of(paid));
    assertThatThrownBy(() -> service.sendReminder(admin, paid.id()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_ALREADY_PAID");
    SaasInvoice waived = inv(InvoiceStatus.WAIVED, null);
    when(invoices.findById(waived.id())).thenReturn(Optional.of(waived));
    assertThatThrownBy(() -> service.sendReminder(admin, waived.id()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(invoices.findByMarkPaidIdempotencyKey("idem")).thenReturn(Optional.of(paid));
    assertThat(service.markPaid(admin, paid.id(), TODAY, "NEFT", "r", "idem"))
        .containsEntry("status", InvoiceStatus.PAID);

    when(invoices.findByMarkPaidIdempotencyKey("idem2")).thenReturn(Optional.empty());
    when(invoices.findById(paid.id())).thenReturn(Optional.of(paid));
    assertThatThrownBy(() -> service.markPaid(admin, paid.id(), TODAY, "NEFT", "r", "idem2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_ALREADY_PAID");

    when(invoices.findByMarkPaidIdempotencyKey("idem3")).thenReturn(Optional.empty());
    when(invoices.findById(waived.id())).thenReturn(Optional.of(waived));
    assertThatThrownBy(() -> service.markPaid(admin, waived.id(), TODAY, "NEFT", "r", "idem3"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(invoices.findByMarkPaidIdempotencyKey("idem4")).thenReturn(Optional.empty());
    when(invoices.findById(due.id())).thenReturn(Optional.of(due));
    assertThatThrownBy(() -> service.markPaid(admin, due.id(), TODAY, " ", "r", "idem4"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void pharmacyListPayAndWrongAccount() {
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    SaasInvoice due = inv(InvoiceStatus.DUE, null);
    when(invoices.listForAccount(accountId, 0, 20)).thenReturn(List.of(due));
    when(invoices.countForAccount(accountId)).thenReturn(1L);
    assertThat(service.listPharmacy(owner, null, null).data().get("invoices")).asList().hasSize(1);

    when(invoices.findById(due.id())).thenReturn(Optional.of(due));
    when(invoices.listLineItems(due.id())).thenReturn(List.of());
    when(checkout.createCheckout(any(), anyLong(), anyString()))
        .thenReturn(
            new InvoiceCheckoutPort.CheckoutSession(
                "https://cashfree.com/checkout/pay_x", NOW.plusSeconds(1800), "Cashfree"));
    when(invoices.findByPayIdempotencyKey("pay1")).thenReturn(Optional.empty());
    Map<String, Object> pay = service.pay(owner, due.id(), "UPI", "pay1");
    assertThat(pay).containsEntry("payment_gateway", "Cashfree");

    when(invoices.findByPayIdempotencyKey("pay1")).thenReturn(Optional.of(due));
    assertThat(service.pay(owner, due.id(), "UPI", "pay1")).containsKey("checkout_url");

    SaasInvoice other =
        new SaasInvoice(
            Ids.newId(),
            "NMM-INV-2026-07-000099",
            Ids.newId(),
            subId,
            PlanNames.STARTER,
            TODAY,
            TODAY.plusMonths(1),
            100,
            SaasGst.RATE_PCT,
            18,
            118,
            InvoiceStatus.DUE,
            TODAY,
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
    when(invoices.findById(other.id())).thenReturn(Optional.of(other));
    when(invoices.findByPayIdempotencyKey("pay2")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.pay(owner, other.id(), "UPI", "pay2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_NOT_FOUND");
    assertThatThrownBy(() -> service.getPharmacy(owner, other.id()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_NOT_FOUND");

    SaasInvoice paid = inv(InvoiceStatus.PAID, NOW);
    when(invoices.findById(paid.id())).thenReturn(Optional.of(paid));
    when(invoices.findByPayIdempotencyKey("pay3")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.pay(owner, paid.id(), null, "pay3"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_ALREADY_PAID");

    SaasInvoice waived = inv(InvoiceStatus.WAIVED, null);
    when(invoices.findById(waived.id())).thenReturn(Optional.of(waived));
    when(invoices.findByPayIdempotencyKey("pay4")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.pay(owner, waived.id(), "UPI", "pay4"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void linesForCycleAndDunningEdgeCases() {
    UUID addonId = Ids.newId();
    SaasPlan plan = new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, 500, true, false, NOW);
    when(plans.listActiveAddons())
        .thenReturn(List.of(new SaasAddon(addonId, "E_INVOICE", 19900, "e", true)));
    when(plans.findActiveAccountAddon(accountId, addonId))
        .thenReturn(Optional.of(new AccountAddon(accountId, addonId, NOW, null)));
    List<InvoiceIssuingPort.LineDraft> lines =
        service.linesForCycle(accountId, plan, BillingCycle.ANNUAL, 699000);
    assertThat(lines).hasSize(2);

    SaasInvoice pastDue = inv(InvoiceStatus.DUE, null);
    pastDue =
        new SaasInvoice(
            pastDue.id(),
            pastDue.invoiceNumber(),
            accountId,
            subId,
            pastDue.planName(),
            pastDue.billingPeriodFrom(),
            pastDue.billingPeriodTo(),
            pastDue.subtotalPaise(),
            pastDue.gstRatePct(),
            pastDue.gstAmountPaise(),
            pastDue.totalAmountPaise(),
            InvoiceStatus.DUE,
            TODAY.minusDays(1),
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
    when(invoices.findOpenPastDue(any())).thenReturn(List.of(pastDue));
    when(invoices.findForDunning(any())).thenReturn(List.of(pastDue));
    service.processDunningJobs();
    verify(invoices, org.mockito.Mockito.atLeastOnce()).update(any());

    when(invoices.findOpenPastDue(any())).thenReturn(List.of());
    SaasInvoice closed = inv(InvoiceStatus.PAID, NOW);
    when(invoices.findForDunning(any())).thenReturn(List.of(closed));
    service.processDunningJobs();

    when(subs.findById(subId)).thenReturn(Optional.empty());
    SaasInvoice day14 =
        new SaasInvoice(
            Ids.newId(),
            "NMM-INV-2026-07-000002",
            accountId,
            subId,
            PlanNames.STARTER,
            TODAY,
            TODAY,
            100,
            SaasGst.RATE_PCT,
            18,
            118,
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
    when(invoices.findForDunning(any())).thenReturn(List.of(day14));
    service.processDunningJobs();

    when(invoices.findById(any())).thenReturn(Optional.of(inv(InvoiceStatus.DUE, null)));
    assertThatThrownBy(() -> service.waive(Ids.newId(), " "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.waive(Ids.newId(), "reason");
  }

  @Test
  void markPaidActivatesAndNullPaymentDate() {
    SaasInvoice due = inv(InvoiceStatus.DUE, null);
    when(invoices.findByMarkPaidIdempotencyKey("k")).thenReturn(Optional.empty());
    when(invoices.findById(due.id())).thenReturn(Optional.of(due));
    when(subs.findById(subId))
        .thenReturn(
            Optional.of(
                new SaasSubscription(
                    subId,
                    accountId,
                    Ids.newId(),
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
                new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, 500, true, false, NOW)));
    when(subs.findPharmacyId(accountId)).thenReturn(Optional.empty());
    assertThat(service.markPaid(admin, due.id(), null, "CASH", "r", "k"))
        .containsEntry("status", InvoiceStatus.PAID);
  }

  private SaasInvoice inv(String status, Instant paidAt) {
    return new SaasInvoice(
        Ids.newId(),
        "NMM-INV-2026-07-000010",
        accountId,
        subId,
        PlanNames.STARTER,
        TODAY,
        TODAY.plusMonths(1),
        69900,
        SaasGst.RATE_PCT,
        SaasGst.gstPaise(69900),
        SaasGst.totalWithGstPaise(69900),
        status,
        TODAY,
        paidAt,
        paidAt == null ? null : "UPI",
        null,
        null,
        0,
        InvoiceStatus.WAIVED.equals(status) ? "w" : null,
        null,
        null,
        null,
        null,
        null,
        NOW,
        NOW);
  }
}
