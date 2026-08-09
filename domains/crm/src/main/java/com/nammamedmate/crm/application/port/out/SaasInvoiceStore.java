package com.nammamedmate.crm.application.port.out;

import com.nammamedmate.crm.domain.SaasInvoice;
import com.nammamedmate.crm.domain.SaasInvoiceLineItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaasInvoiceStore {

  record AdminListFilter(
      String status,
      String plan,
      UUID accountId,
      LocalDate from,
      LocalDate to,
      int offset,
      int limit) {}

  record BillingChips(
      long collectedPaise,
      long duePaise,
      long overduePaise,
      BigDecimal collectionRatePct,
      BigDecimal dsoDays,
      long inDunningCount) {}

  record PlanCollected(String plan, long collectedPaise) {}

  record PharmacyBillingProfile(
      String pharmacyName, String billingAddress, String gstin, UUID pharmacyId) {}

  void insert(SaasInvoice invoice, List<SaasInvoiceLineItem> lines);

  void update(SaasInvoice invoice);

  Optional<SaasInvoice> findById(UUID id);

  Optional<SaasInvoice> findByMarkPaidIdempotencyKey(String key);

  Optional<SaasInvoice> findByPayIdempotencyKey(String key);

  List<SaasInvoiceLineItem> listLineItems(UUID invoiceId);

  List<SaasInvoice> listAdmin(AdminListFilter filter);

  long countAdmin(AdminListFilter filter);

  BillingChips chips(LocalDate from, LocalDate to);

  List<PlanCollected> collectedByPlan(LocalDate from, LocalDate to);

  List<SaasInvoice> listForAccount(UUID accountId, int offset, int limit);

  long countForAccount(UUID accountId);

  /** Open invoice statuses for an account (DUE / OVERDUE / DUNNING). */
  List<String> listOpenStatuses(UUID accountId);

  PharmacyBillingProfile pharmacyProfile(UUID accountId);

  /** Next monthly sequence value (1-based) for invoice number. */
  int nextInvoiceSeq(String yearMonth);

  List<SaasInvoice> findOpenPastDue(LocalDate asOf);

  List<SaasInvoice> findForDunning(LocalDate asOf);
}
