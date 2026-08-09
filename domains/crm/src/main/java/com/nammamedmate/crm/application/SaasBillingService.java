package com.nammamedmate.crm.application;

import com.nammamedmate.crm.adapter.out.export.SimplePdfExporter;
import com.nammamedmate.crm.application.port.out.CrmSubscriptionOutboxPort;
import com.nammamedmate.crm.application.port.out.InvoiceCheckoutPort;
import com.nammamedmate.crm.application.port.out.InvoiceIssuingPort;
import com.nammamedmate.crm.application.port.out.PharmacyPlanSyncPort;
import com.nammamedmate.crm.application.port.out.SaasInvoicePdfPort;
import com.nammamedmate.crm.application.port.out.SaasInvoiceStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.application.port.out.SaasSubscriptionStore;
import com.nammamedmate.crm.domain.AccountAddon;
import com.nammamedmate.crm.domain.CrmMoney;
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
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaasBillingService implements InvoiceIssuingPort {

  static final int[] DUNNING_DAYS = {0, 3, 7, 10, 14};
  static final Duration PDF_TTL = Duration.ofHours(1);

  private final SaasInvoiceStore invoices;
  private final SaasPlanStore plans;
  private final SaasSubscriptionStore subs;
  private final SaasInvoicePdfPort pdfs;
  private final InvoiceCheckoutPort checkout;
  private final CrmSubscriptionOutboxPort outbox;
  private final PharmacyPlanSyncPort planSync;
  private final Clock clock;

  public SaasBillingService(
      SaasInvoiceStore invoices,
      SaasPlanStore plans,
      SaasSubscriptionStore subs,
      SaasInvoicePdfPort pdfs,
      InvoiceCheckoutPort checkout,
      CrmSubscriptionOutboxPort outbox,
      PharmacyPlanSyncPort planSync,
      Clock clock) {
    this.invoices = invoices;
    this.plans = plans;
    this.subs = subs;
    this.pdfs = pdfs;
    this.checkout = checkout;
    this.outbox = outbox;
    this.planSync = planSync;
    this.clock = clock;
  }

  @Override
  @Transactional
  public UUID issue(
      UUID accountId,
      UUID subscriptionId,
      String planName,
      LocalDate periodFrom,
      LocalDate periodTo,
      LocalDate dueDate,
      List<LineDraft> lines,
      String status,
      Instant paidAt,
      String paymentMode,
      String referenceNumber) {
    Instant now = clock.instant();
    if (lines == null || lines.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "invoice requires line items", 400);
    }
    long subtotal = 0L;
    for (LineDraft line : lines) {
      subtotal = Math.addExact(subtotal, line.amountPaise());
    }
    long gst = SaasGst.gstPaise(Math.max(0, subtotal));
    long total = Math.addExact(Math.max(0, subtotal), gst);
    String yearMonth = periodFrom.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    int seq = invoices.nextInvoiceSeq(yearMonth);
    String number = String.format(Locale.ROOT, "NMM-INV-%s-%06d", yearMonth, seq);
    UUID id = Ids.newId();
    String resolvedStatus =
        InvoiceStatus.PAID.equals(status) ? InvoiceStatus.PAID : InvoiceStatus.DUE;
    SaasInvoice invoice =
        new SaasInvoice(
            id,
            number,
            accountId,
            subscriptionId,
            planName,
            periodFrom,
            periodTo,
            Math.max(0, subtotal),
            SaasGst.RATE_PCT,
            gst,
            total,
            resolvedStatus,
            dueDate,
            InvoiceStatus.PAID.equals(resolvedStatus) ? (paidAt == null ? now : paidAt) : null,
            paymentMode,
            referenceNumber,
            null,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now);
    List<SaasInvoiceLineItem> items = new ArrayList<>();
    for (LineDraft draft : lines) {
      items.add(
          new SaasInvoiceLineItem(
              Ids.newId(),
              id,
              draft.description(),
              SaasGst.SAC_CODE,
              draft.amountPaise(),
              InvoiceLineItemType.requireValid(draft.itemType()),
              now));
    }
    invoices.insert(invoice, items);
    ensurePdf(invoice, items);
    return id;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public PagedResult listAdmin(
      MedmatePrincipal principal,
      String status,
      String plan,
      UUID accountId,
      LocalDate from,
      LocalDate to,
      Integer page,
      Integer limit) {
    requireAdminRead(principal);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    String statusFilter =
        status == null || status.isBlank() ? null : InvoiceStatus.requireValid(status);
    SaasInvoiceStore.AdminListFilter filter =
        new SaasInvoiceStore.AdminListFilter(
            statusFilter, plan, accountId, from, to, (p - 1) * lim, lim);
    List<SaasInvoice> rows = invoices.listAdmin(filter);
    long total = invoices.countAdmin(filter);
    SaasInvoiceStore.BillingChips chips = invoices.chips(from, to);
    List<Map<String, Object>> invoiceRows = new ArrayList<>();
    for (SaasInvoice inv : rows) {
      SaasInvoiceStore.PharmacyBillingProfile profile = invoices.pharmacyProfile(inv.accountId());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", inv.id());
      row.put("account_id", inv.accountId());
      row.put("pharmacy_name", profile.pharmacyName());
      row.put("plan", inv.planName());
      row.put("amount_incl_gst_rs", CrmMoney.paiseToRupees(inv.totalAmountPaise()));
      row.put("billing_period", inv.billingPeriodFrom() + " to " + inv.billingPeriodTo());
      row.put("status", inv.status());
      row.put("due_date", inv.dueAt());
      if (inv.paidAt() != null) {
        row.put("paid_at", inv.paidAt());
      }
      invoiceRows.add(row);
    }
    List<Map<String, Object>> byPlan = new ArrayList<>();
    for (SaasInvoiceStore.PlanCollected pc : invoices.collectedByPlan(from, to)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("plan", pc.plan());
      row.put("collected_rs", CrmMoney.paiseToRupees(pc.collectedPaise()));
      byPlan.add(row);
    }
    Map<String, Object> chipMap = new LinkedHashMap<>();
    chipMap.put("collected_rs", CrmMoney.paiseToRupees(chips.collectedPaise()));
    chipMap.put("due_rs", CrmMoney.paiseToRupees(chips.duePaise()));
    chipMap.put("overdue_rs", CrmMoney.paiseToRupees(chips.overduePaise()));
    chipMap.put("collection_rate_pct", chips.collectionRatePct());
    chipMap.put("dso_days", chips.dsoDays());
    chipMap.put("in_dunning_count", chips.inDunningCount());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("chips", chipMap);
    data.put("collected_by_plan", byPlan);
    data.put("invoices", invoiceRows);
    return new PagedResult(data, PaginationMeta.of(p, lim, total));
  }

  public Map<String, Object> getAdmin(MedmatePrincipal principal, UUID id) {
    requireAdminRead(principal);
    SaasInvoice inv = requireInvoice(id);
    return detailAdmin(inv);
  }

  @Transactional
  public Map<String, Object> sendReminder(MedmatePrincipal principal, UUID id) {
    requireAdminRead(principal);
    SaasInvoice inv = requireInvoice(id);
    if (InvoiceStatus.PAID.equals(inv.status())) {
      throw new AppException("INVOICE_ALREADY_PAID", "Cannot send reminder on a PAID invoice", 400);
    }
    if (InvoiceStatus.WAIVED.equals(inv.status())) {
      throw new AppException("VALIDATION_ERROR", "Cannot remind on a WAIVED invoice", 400);
    }
    Instant now = clock.instant();
    outbox.publish(
        "crm.invoice.payment_reminder",
        inv.id(),
        Map.of(
            "invoice_id", inv.id().toString(),
            "account_id", inv.accountId().toString(),
            "channels", List.of("EMAIL", "WHATSAPP")));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invoice_id", inv.id());
    data.put("reminder_sent_via", List.of("EMAIL", "WHATSAPP"));
    data.put("sent_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> markPaid(
      MedmatePrincipal principal,
      UUID id,
      LocalDate paymentDate,
      String paymentMode,
      String referenceNumber,
      String idempotencyKey) {
    requireMarkPaid(principal);
    String key = requireIdempotencyKey(idempotencyKey);
    Optional<SaasInvoice> replay = invoices.findByMarkPaidIdempotencyKey(key);
    if (replay.isPresent()) {
      return markPaidResponse(replay.get(), principal.subject());
    }
    SaasInvoice inv = requireInvoice(id);
    if (InvoiceStatus.PAID.equals(inv.status())) {
      throw new AppException("INVOICE_ALREADY_PAID", "Invoice already paid", 400);
    }
    if (InvoiceStatus.WAIVED.equals(inv.status())) {
      throw new AppException("VALIDATION_ERROR", "Cannot mark a WAIVED invoice paid", 400);
    }
    Instant now = clock.instant();
    Instant paidAt =
        paymentDate == null ? now : paymentDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    if (paymentMode == null || paymentMode.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "payment_mode required", 400);
    }
    SaasInvoice updated =
        new SaasInvoice(
            inv.id(),
            inv.invoiceNumber(),
            inv.accountId(),
            inv.subscriptionId(),
            inv.planName(),
            inv.billingPeriodFrom(),
            inv.billingPeriodTo(),
            inv.subtotalPaise(),
            inv.gstRatePct(),
            inv.gstAmountPaise(),
            inv.totalAmountPaise(),
            InvoiceStatus.PAID,
            inv.dueAt(),
            paidAt,
            paymentMode.trim(),
            referenceNumber,
            principal.subject(),
            inv.dunningStep(),
            inv.waiveReason(),
            inv.pdfObjectKey(),
            inv.checkoutUrl(),
            inv.checkoutExpiresAt(),
            key,
            inv.payIdempotencyKey(),
            inv.createdAt(),
            now);
    invoices.update(updated);
    activateSubscription(inv.subscriptionId(), now);
    return markPaidResponse(updated, principal.subject());
  }

  public PagedResult listPharmacy(MedmatePrincipal principal, Integer page, Integer limit) {
    requireOwner(principal);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    UUID accountId = requireAccountId(principal.pharmacyId());
    List<SaasInvoice> rows = invoices.listForAccount(accountId, (p - 1) * lim, lim);
    long total = invoices.countForAccount(accountId);
    List<Map<String, Object>> list = new ArrayList<>();
    DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    for (SaasInvoice inv : rows) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", inv.id());
      row.put("invoice_number", inv.invoiceNumber());
      row.put("billing_period", inv.billingPeriodFrom().format(monthFmt));
      row.put("total_amount_rs", CrmMoney.paiseToRupees(inv.totalAmountPaise()));
      row.put("status", inv.status());
      row.put("due_date", inv.dueAt());
      if (inv.paidAt() != null) {
        row.put("paid_at", inv.paidAt());
      }
      list.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invoices", list);
    return new PagedResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> getPharmacy(MedmatePrincipal principal, UUID id) {
    requireOwner(principal);
    UUID accountId = requireAccountId(principal.pharmacyId());
    SaasInvoice inv = requireInvoice(id);
    if (!inv.accountId().equals(accountId)) {
      throw new AppException("INVOICE_NOT_FOUND", "Invoice does not belong to pharmacy", 404);
    }
    List<SaasInvoiceLineItem> lines = invoices.listLineItems(inv.id());
    SaasInvoice withPdf = ensurePdf(inv, lines);
    SaasInvoicePdfPort.SignedUrl signed = pdfs.signedGet(withPdf.pdfObjectKey(), PDF_TTL);
    List<Map<String, Object>> lineRows = new ArrayList<>();
    for (SaasInvoiceLineItem line : lines) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("description", line.description());
      row.put("amount_rs", CrmMoney.paiseToRupees(line.amountPaise()));
      lineRows.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", withPdf.id());
    data.put("invoice_number", withPdf.invoiceNumber());
    data.put("download_url", signed.url());
    data.put("download_expires_at", signed.expiresAt());
    data.put("line_items", lineRows);
    data.put("subtotal_rs", CrmMoney.paiseToRupees(withPdf.subtotalPaise()));
    data.put("gst_amount_rs", CrmMoney.paiseToRupees(withPdf.gstAmountPaise()));
    data.put("total_amount_rs", CrmMoney.paiseToRupees(withPdf.totalAmountPaise()));
    data.put("status", withPdf.status());
    return data;
  }

  @Transactional
  public Map<String, Object> pay(
      MedmatePrincipal principal, UUID invoiceId, String paymentMethod, String idempotencyKey) {
    requireOwner(principal);
    String key = requireIdempotencyKey(idempotencyKey);
    Optional<SaasInvoice> replay = invoices.findByPayIdempotencyKey(key);
    if (replay.isPresent()) {
      return payResponse(replay.get());
    }
    UUID accountId = requireAccountId(principal.pharmacyId());
    SaasInvoice inv = requireInvoice(invoiceId);
    if (!inv.accountId().equals(accountId)) {
      throw new AppException("INVOICE_NOT_FOUND", "Invoice does not belong to pharmacy", 404);
    }
    if (InvoiceStatus.PAID.equals(inv.status())) {
      throw new AppException("INVOICE_ALREADY_PAID", "Invoice already in PAID status", 400);
    }
    if (InvoiceStatus.WAIVED.equals(inv.status())) {
      throw new AppException("VALIDATION_ERROR", "Cannot pay a WAIVED invoice", 400);
    }
    Instant now = clock.instant();
    InvoiceCheckoutPort.CheckoutSession session =
        checkout.createCheckout(
            inv.id(),
            inv.totalAmountPaise(),
            paymentMethod == null || paymentMethod.isBlank() ? "UPI" : paymentMethod.trim());
    SaasInvoice updated =
        new SaasInvoice(
            inv.id(),
            inv.invoiceNumber(),
            inv.accountId(),
            inv.subscriptionId(),
            inv.planName(),
            inv.billingPeriodFrom(),
            inv.billingPeriodTo(),
            inv.subtotalPaise(),
            inv.gstRatePct(),
            inv.gstAmountPaise(),
            inv.totalAmountPaise(),
            inv.status(),
            inv.dueAt(),
            inv.paidAt(),
            inv.paymentMode(),
            inv.referenceNumber(),
            inv.markedPaidBy(),
            inv.dunningStep(),
            inv.waiveReason(),
            inv.pdfObjectKey(),
            session.checkoutUrl(),
            session.expiresAt(),
            inv.markPaidIdempotencyKey(),
            key,
            inv.createdAt(),
            now);
    invoices.update(updated);
    return payResponse(updated);
  }

  /** Test / admin helper: waive an invoice (excluded from overdue/collection metrics). */
  @Transactional
  public SaasInvoice waive(UUID invoiceId, String reason) {
    SaasInvoice inv = requireInvoice(invoiceId);
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "waive_reason required", 400);
    }
    Instant now = clock.instant();
    SaasInvoice updated =
        new SaasInvoice(
            inv.id(),
            inv.invoiceNumber(),
            inv.accountId(),
            inv.subscriptionId(),
            inv.planName(),
            inv.billingPeriodFrom(),
            inv.billingPeriodTo(),
            inv.subtotalPaise(),
            inv.gstRatePct(),
            inv.gstAmountPaise(),
            inv.totalAmountPaise(),
            InvoiceStatus.WAIVED,
            inv.dueAt(),
            inv.paidAt(),
            inv.paymentMode(),
            inv.referenceNumber(),
            inv.markedPaidBy(),
            inv.dunningStep(),
            reason.trim(),
            inv.pdfObjectKey(),
            inv.checkoutUrl(),
            inv.checkoutExpiresAt(),
            inv.markPaidIdempotencyKey(),
            inv.payIdempotencyKey(),
            inv.createdAt(),
            now);
    invoices.update(updated);
    return updated;
  }

  @Transactional
  public void processDunningJobs() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    Instant now = clock.instant();
    for (SaasInvoice inv : invoices.findOpenPastDue(today)) {
      invoices.update(withStatus(inv, InvoiceStatus.OVERDUE, inv.dunningStep(), now));
    }
    for (SaasInvoice inv : invoices.findForDunning(today)) {
      if (InvoiceStatus.isClosed(inv.status())) {
        continue;
      }
      long daysLate = ChronoUnit.DAYS.between(inv.dueAt(), today);
      int targetStep = dunningStepForDays(daysLate);
      // Steps 1..4 advance on Day 3/7/10/14; step 0 is the initial due state.
      if (targetStep <= inv.dunningStep()) {
        continue;
      }
      SaasInvoice updated = withStatus(inv, InvoiceStatus.DUNNING, targetStep, now);
      invoices.update(updated);
      outbox.publish(
          "crm.invoice.dunning_step",
          inv.id(),
          Map.of(
              "invoice_id",
              inv.id().toString(),
              "account_id",
              inv.accountId().toString(),
              "dunning_step",
              targetStep,
              "days_late",
              daysLate));
      if (targetStep >= 4) {
        expireSubscription(inv.subscriptionId(), now);
      }
    }
  }

  static int dunningStepForDays(long daysLate) {
    int step = 0;
    for (int i = 0; i < DUNNING_DAYS.length; i++) {
      if (daysLate >= DUNNING_DAYS[i]) {
        step = i;
      }
    }
    return step;
  }

  /** Build plan + active addon line drafts for a billing cycle amount. */
  public List<LineDraft> linesForCycle(
      UUID accountId, SaasPlan plan, String billingCycle, long planAmountPaise) {
    List<LineDraft> lines = new ArrayList<>();
    String cycleLabel = "ANNUAL".equals(billingCycle) ? "Annual" : "Monthly";
    lines.add(
        new LineDraft(
            plan.name() + " Plan - " + cycleLabel, planAmountPaise, InvoiceLineItemType.PLAN));
    for (SaasAddon addon : plans.listActiveAddons()) {
      Optional<AccountAddon> aa = plans.findActiveAccountAddon(accountId, addon.id());
      if (aa.isPresent()) {
        long addonAmt =
            "ANNUAL".equals(billingCycle)
                ? CrmMoney.annualPaise(addon.priceMonthlyPaise())
                : addon.priceMonthlyPaise();
        lines.add(
            new LineDraft(
                addon.name().replace('_', ' ') + " Add-on", addonAmt, InvoiceLineItemType.ADDON));
      }
    }
    return lines;
  }

  private Map<String, Object> detailAdmin(SaasInvoice inv) {
    SaasInvoiceStore.PharmacyBillingProfile profile = invoices.pharmacyProfile(inv.accountId());
    List<SaasInvoiceLineItem> lines = invoices.listLineItems(inv.id());
    List<Map<String, Object>> lineRows = new ArrayList<>();
    for (SaasInvoiceLineItem line : lines) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("description", line.description());
      row.put("amount_rs", CrmMoney.paiseToRupees(line.amountPaise()));
      row.put("sac_code", line.sacCode());
      lineRows.add(row);
    }
    List<Map<String, Object>> nextAddons = new ArrayList<>();
    for (SaasAddon addon : plans.listActiveAddons()) {
      Optional<AccountAddon> aa = plans.findActiveAccountAddon(inv.accountId(), addon.id());
      if (aa.isPresent()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", addon.name());
        row.put("amount_rs", CrmMoney.paiseToRupees(addon.priceMonthlyPaise()));
        nextAddons.add(row);
      }
    }
    Map<String, Object> period = new LinkedHashMap<>();
    period.put("from", inv.billingPeriodFrom());
    period.put("to", inv.billingPeriodTo());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", inv.id());
    data.put("invoice_number", inv.invoiceNumber());
    data.put("account_id", inv.accountId());
    data.put("pharmacy_name", profile.pharmacyName());
    data.put("billing_address", profile.billingAddress());
    data.put("gstin", profile.gstin());
    data.put("billing_period", period);
    data.put("line_items", lineRows);
    data.put("subtotal_rs", CrmMoney.paiseToRupees(inv.subtotalPaise()));
    data.put("gst_rate_pct", inv.gstRatePct());
    data.put("gst_amount_rs", CrmMoney.paiseToRupees(inv.gstAmountPaise()));
    data.put("total_amount_rs", CrmMoney.paiseToRupees(inv.totalAmountPaise()));
    data.put("status", inv.status());
    data.put("due_date", inv.dueAt());
    data.put("paid_at", inv.paidAt());
    data.put("payment_mode", inv.paymentMode());
    data.put("reference_number", inv.referenceNumber());
    data.put("next_cycle_addons", nextAddons);
    return data;
  }

  private SaasInvoice ensurePdf(SaasInvoice inv, List<SaasInvoiceLineItem> lines) {
    if (inv.pdfObjectKey() != null && !inv.pdfObjectKey().isBlank()) {
      return inv;
    }
    List<String> pdfLines = new ArrayList<>();
    pdfLines.add("Invoice: " + inv.invoiceNumber());
    pdfLines.add("Period: " + inv.billingPeriodFrom() + " to " + inv.billingPeriodTo());
    pdfLines.add("SAC: " + SaasGst.SAC_CODE);
    for (SaasInvoiceLineItem line : lines) {
      pdfLines.add(line.description() + "  Rs " + CrmMoney.paiseToRupees(line.amountPaise()));
    }
    pdfLines.add("Subtotal: Rs " + CrmMoney.paiseToRupees(inv.subtotalPaise()));
    pdfLines.add("GST 18%: Rs " + CrmMoney.paiseToRupees(inv.gstAmountPaise()));
    pdfLines.add("Total: Rs " + CrmMoney.paiseToRupees(inv.totalAmountPaise()));
    byte[] bytes = SimplePdfExporter.export("Namma MedMate SaaS Invoice", pdfLines);
    String key = inv.invoiceNumber() + ".pdf";
    pdfs.put(key, bytes);
    Instant now = clock.instant();
    SaasInvoice updated =
        new SaasInvoice(
            inv.id(),
            inv.invoiceNumber(),
            inv.accountId(),
            inv.subscriptionId(),
            inv.planName(),
            inv.billingPeriodFrom(),
            inv.billingPeriodTo(),
            inv.subtotalPaise(),
            inv.gstRatePct(),
            inv.gstAmountPaise(),
            inv.totalAmountPaise(),
            inv.status(),
            inv.dueAt(),
            inv.paidAt(),
            inv.paymentMode(),
            inv.referenceNumber(),
            inv.markedPaidBy(),
            inv.dunningStep(),
            inv.waiveReason(),
            key,
            inv.checkoutUrl(),
            inv.checkoutExpiresAt(),
            inv.markPaidIdempotencyKey(),
            inv.payIdempotencyKey(),
            inv.createdAt(),
            now);
    invoices.update(updated);
    return updated;
  }

  private void activateSubscription(UUID subscriptionId, Instant now) {
    SaasSubscription sub =
        subs.findById(subscriptionId)
            .orElseThrow(
                () -> new AppException("SUBSCRIPTION_NOT_FOUND", "Subscription not found", 404));
    SaasPlan plan =
        plans
            .findPlanById(sub.planId())
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    SaasSubscription updated =
        new SaasSubscription(
            sub.id(),
            sub.accountId(),
            sub.planId(),
            sub.scheduledPlanId(),
            SubscriptionStatus.ACTIVE,
            sub.billingCycle(),
            sub.renewalDate(),
            sub.trialEndsAt(),
            sub.autoRenew(),
            sub.cancelledAt(),
            sub.cancelsAt(),
            null,
            null,
            sub.lastInvoiceId(),
            sub.overridePlanId(),
            sub.overrideExpiresAt(),
            sub.overrideReason(),
            sub.createdAt(),
            now);
    subs.update(updated);
    subs.updateAccountDenorm(sub.accountId(), plan.name(), SubscriptionStatus.ACTIVE, now);
    subs.findPharmacyId(sub.accountId()).ifPresent(pid -> planSync.syncPlan(pid, plan.name()));
  }

  private void expireSubscription(UUID subscriptionId, Instant now) {
    SaasSubscription sub = subs.findById(subscriptionId).orElse(null);
    if (sub == null || sub.overrideActive(now)) {
      return;
    }
    SaasPlan free =
        plans
            .findPlanByName(PlanNames.FREE)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "FREE plan missing", 404));
    SaasSubscription updated =
        new SaasSubscription(
            sub.id(),
            sub.accountId(),
            free.id(),
            null,
            SubscriptionStatus.EXPIRED,
            sub.billingCycle(),
            sub.renewalDate(),
            null,
            false,
            sub.cancelledAt(),
            sub.cancelsAt(),
            now,
            sub.pastDueAt(),
            sub.lastInvoiceId(),
            sub.overridePlanId(),
            sub.overrideExpiresAt(),
            sub.overrideReason(),
            sub.createdAt(),
            now);
    subs.update(updated);
    subs.updateAccountDenorm(sub.accountId(), PlanNames.FREE, SubscriptionStatus.EXPIRED, now);
    subs.findPharmacyId(sub.accountId()).ifPresent(pid -> planSync.syncPlan(pid, PlanNames.FREE));
    outbox.publish(
        "crm.subscription.expired",
        sub.id(),
        Map.of(
            "subscription_id", sub.id().toString(),
            "account_id", sub.accountId().toString(),
            "reason", "dunning_day_14"));
  }

  private static SaasInvoice withStatus(
      SaasInvoice inv, String status, int dunningStep, Instant now) {
    return new SaasInvoice(
        inv.id(),
        inv.invoiceNumber(),
        inv.accountId(),
        inv.subscriptionId(),
        inv.planName(),
        inv.billingPeriodFrom(),
        inv.billingPeriodTo(),
        inv.subtotalPaise(),
        inv.gstRatePct(),
        inv.gstAmountPaise(),
        inv.totalAmountPaise(),
        status,
        inv.dueAt(),
        inv.paidAt(),
        inv.paymentMode(),
        inv.referenceNumber(),
        inv.markedPaidBy(),
        dunningStep,
        inv.waiveReason(),
        inv.pdfObjectKey(),
        inv.checkoutUrl(),
        inv.checkoutExpiresAt(),
        inv.markPaidIdempotencyKey(),
        inv.payIdempotencyKey(),
        inv.createdAt(),
        now);
  }

  private Map<String, Object> markPaidResponse(SaasInvoice inv, UUID markedBy) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invoice_id", inv.id());
    data.put("status", InvoiceStatus.PAID);
    data.put("paid_at", inv.paidAt());
    data.put("marked_by", markedBy);
    data.put("subscription_status_updated_to", SubscriptionStatus.ACTIVE);
    return data;
  }

  private Map<String, Object> payResponse(SaasInvoice inv) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invoice_id", inv.id());
    data.put("payment_gateway", "Razorpay");
    data.put("checkout_url", inv.checkoutUrl());
    data.put("expires_at", inv.checkoutExpiresAt());
    return data;
  }

  private SaasInvoice requireInvoice(UUID id) {
    return invoices
        .findById(id)
        .orElseThrow(() -> new AppException("INVOICE_NOT_FOUND", "Invoice ID does not exist", 404));
  }

  private UUID requireAccountId(UUID pharmacyId) {
    return plans
        .findAccountByPharmacyId(pharmacyId)
        .orElseThrow(() -> new AppException("ACCOUNT_NOT_FOUND", "Account not found", 404))
        .id();
  }

  private static String requireIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key is required", 400);
    }
    if (key.length() > 128) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key max 128 characters", 400);
    }
    return key.trim();
  }

  private static void requireAdminRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_FINANCE
        && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
  }

  private static void requireMarkPaid(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Admin finance access required", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Admin finance access required", 403);
    }
  }

  private static void requireOwner(MedmatePrincipal principal) {
    if (principal == null
        || principal.role() != AuthRole.PHARMACY_OWNER
        || principal.pharmacyId() == null) {
      throw new AppException("FORBIDDEN", "Pharmacy owner access required", 403);
    }
  }
}
