package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
class SaasBillingServiceGapsTest {

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
    owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "o");
    when(invoices.nextInvoiceSeq(any())).thenReturn(1);
    when(pdfs.signedGet(any(), any()))
        .thenReturn(new SaasInvoicePdfPort.SignedUrl("file:///x", NOW.plusSeconds(10)));
  }

  @Test
  void remainingBranches() {
    assertThatThrownBy(
            () ->
                service.issue(
                    accountId, subId, "S", TODAY, TODAY, TODAY, null, "DUE", null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    service.issue(
        accountId,
        subId,
        PlanNames.STARTER,
        TODAY,
        TODAY.plusMonths(1),
        TODAY,
        List.of(new InvoiceIssuingPort.LineDraft("p", 100, InvoiceLineItemType.PLAN)),
        InvoiceStatus.PAID,
        null,
        "UPI",
        "r");
    service.issue(
        accountId,
        subId,
        PlanNames.STARTER,
        TODAY,
        TODAY.plusMonths(1),
        TODAY,
        List.of(new InvoiceIssuingPort.LineDraft("p", 100, InvoiceLineItemType.PLAN)),
        InvoiceStatus.PAID,
        NOW,
        "UPI",
        "r");
    service.issue(
        accountId,
        subId,
        PlanNames.STARTER,
        TODAY,
        TODAY.plusMonths(1),
        TODAY,
        List.of(new InvoiceIssuingPort.LineDraft("p", 100, InvoiceLineItemType.PLAN)),
        InvoiceStatus.DUE,
        null,
        null,
        null);

    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(invoices.listForAccount(accountId, 0, 20)).thenReturn(List.of());
    when(invoices.countForAccount(accountId)).thenReturn(0L);
    assertThat(service.listPharmacy(owner, 0, 0).meta().limit()).isEqualTo(20);

    SaasInvoice due = inv(null);
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
    assertThat(service.getPharmacy(owner, due.id()).get("line_items")).asList().hasSize(1);

    when(plans.findAccountByPharmacyId(pharmacyId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.listPharmacy(owner, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_NOT_FOUND");

    assertThatThrownBy(() -> service.markPaid(null, due.id(), TODAY, "NEFT", "r", "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(invoices.findByMarkPaidIdempotencyKey("k")).thenReturn(Optional.empty());
    when(invoices.findById(due.id())).thenReturn(Optional.of(due));
    when(subs.findById(subId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.markPaid(admin, due.id(), TODAY, "NEFT", "r", "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUBSCRIPTION_NOT_FOUND");

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
    when(plans.findPlanById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.markPaid(admin, due.id(), TODAY, "NEFT", "r", "k2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    // expire with override active skips
    SaasInvoice day14 =
        new SaasInvoice(
            Ids.newId(),
            "NMM-INV-2026-07-000099",
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
    when(invoices.findOpenPastDue(any())).thenReturn(List.of());
    when(invoices.findForDunning(any())).thenReturn(List.of(day14));
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
                    day14.id(),
                    Ids.newId(),
                    NOW.plusSeconds(3600),
                    "override",
                    NOW,
                    NOW)));
    service.processDunningJobs();

    // FREE plan missing on expire
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
                    day14.id(),
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    when(plans.findPlanByName(PlanNames.FREE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.processDunningJobs())
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    SaasPlan plan = new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, 500, true, false, NOW);
    UUID addonId = Ids.newId();
    UUID otherAddon = Ids.newId();
    when(plans.listActiveAddons())
        .thenReturn(
            List.of(
                new com.nammamedmate.crm.domain.SaasAddon(addonId, "E_INVOICE", 19900, "e", true),
                new com.nammamedmate.crm.domain.SaasAddon(otherAddon, "X", 100, "x", true)));
    when(plans.findActiveAccountAddon(accountId, addonId))
        .thenReturn(
            Optional.of(
                new com.nammamedmate.crm.domain.AccountAddon(accountId, addonId, NOW, null)));
    when(plans.findActiveAccountAddon(accountId, otherAddon)).thenReturn(Optional.empty());
    assertThat(service.linesForCycle(accountId, plan, BillingCycle.MONTHLY, 69900)).hasSize(2);
    assertThat(service.linesForCycle(accountId, plan, BillingCycle.ANNUAL, 699000)).hasSize(2);

    when(invoices.chips(any(), any()))
        .thenReturn(
            new SaasInvoiceStore.BillingChips(
                0, 0, 0, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 0));
    when(invoices.collectedByPlan(any(), any())).thenReturn(List.of());
    SaasInvoice paidRow =
        new SaasInvoice(
            Ids.newId(),
            "NMM-INV-2026-07-000077",
            accountId,
            subId,
            PlanNames.STARTER,
            TODAY,
            TODAY.plusMonths(1),
            69900,
            SaasGst.RATE_PCT,
            SaasGst.gstPaise(69900),
            SaasGst.totalWithGstPaise(69900),
            InvoiceStatus.PAID,
            TODAY,
            NOW,
            "UPI",
            "r",
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
    when(invoices.listAdmin(any())).thenReturn(List.of(paidRow));
    when(invoices.countAdmin(any())).thenReturn(1L);
    when(invoices.pharmacyProfile(accountId))
        .thenReturn(
            new SaasInvoiceStore.PharmacyBillingProfile("Apollo", "HSR", "29X", pharmacyId));
    service.listAdmin(admin, " ", null, null, null, null, 2, 5);
    assertThat(new SaasBillingService.PagedResult(null, null).data()).isEmpty();
    service.listAdmin(
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f"),
        "DUE",
        null,
        null,
        null,
        null,
        1,
        20);

    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(invoices.listForAccount(accountId, 0, 20)).thenReturn(List.of(paidRow));
    when(invoices.countForAccount(accountId)).thenReturn(1L);
    assertThat(service.listPharmacy(owner, 1, 20).data().get("invoices")).asList().hasSize(1);
    SaasInvoice due2 = inv(null);
    when(invoices.findById(due2.id())).thenReturn(Optional.of(due2));
    when(invoices.findByPayIdempotencyKey("pm")).thenReturn(Optional.empty());
    when(checkout.createCheckout(any(), anyLong(), anyString()))
        .thenReturn(
            new InvoiceCheckoutPort.CheckoutSession(
                "https://rzp/x", NOW.plusSeconds(60), "Razorpay"));
    service.pay(owner, due2.id(), " ", "pm");
    when(invoices.findByPayIdempotencyKey("pm2")).thenReturn(Optional.empty());
    service.pay(owner, due2.id(), "CARD", "pm2");
    when(invoices.findByPayIdempotencyKey("pm3")).thenReturn(Optional.empty());
    service.pay(owner, due2.id(), null, "pm3");
    service.listAdmin(admin, null, null, null, null, null, 1, 200);

    SaasInvoice withBlankPdf = inv("  ");
    when(invoices.findById(withBlankPdf.id())).thenReturn(Optional.of(withBlankPdf));
    when(invoices.listLineItems(withBlankPdf.id())).thenReturn(List.of());
    service.getPharmacy(owner, withBlankPdf.id());

    when(invoices.findByMarkPaidIdempotencyKey("nullmode")).thenReturn(Optional.empty());
    when(invoices.findById(due2.id())).thenReturn(Optional.of(due2));
    assertThatThrownBy(() -> service.markPaid(admin, due2.id(), TODAY, null, "r", "nullmode"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(invoices.findById(due2.id())).thenReturn(Optional.of(due2));
    assertThatThrownBy(() -> service.waive(due2.id(), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.markPaid(admin, due2.id(), TODAY, "NEFT", "r", " "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.listPharmacy(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "x"),
                    1,
                    20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.listPharmacy(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "s"),
                    1,
                    20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  private SaasInvoice inv(String pdfKey) {
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
        InvoiceStatus.DUE,
        TODAY,
        null,
        null,
        null,
        null,
        0,
        null,
        pdfKey,
        null,
        null,
        null,
        null,
        NOW,
        NOW);
  }
}
