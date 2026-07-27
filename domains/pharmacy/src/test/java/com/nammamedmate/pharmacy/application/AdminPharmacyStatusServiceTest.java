package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminListRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.ListFilter;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.PageResult;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.ZoneRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminPharmacyStatusServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:10:00Z");
  private static final UUID ZONE = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private FakeAdminStore store;
  private FakeZones zones;
  private FakeAudit audit;
  private InMemoryOutboxStore outboxStore;
  private RateLimiter rateLimiter;
  private AutoKycService autoKyc;
  private AdminPharmacyStatusService service;

  @BeforeEach
  void setUp() {
    store = new FakeAdminStore();
    zones = new FakeZones();
    zones.put(ZONE, new ZoneRecord(ZONE, "Koramangala Zone", true));
    audit = new FakeAudit();
    outboxStore = new InMemoryOutboxStore();
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    autoKyc = mock(AutoKycService.class);
    when(autoKyc.latestAutoKycSummary(any())).thenReturn(Map.of("overall_status", "PARTIAL"));
    service =
        new AdminPharmacyStatusService(
            store,
            zones,
            audit,
            autoKyc,
            rateLimiter,
            new OutboxPublisher(outboxStore, new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void approveActivatesPharmacyAndNotifies() {
    store.put(detail("KYC_SUBMITTED"));
    Map<String, Object> data =
        service.approve(ops(), PID, new BigDecimal("8.00"), ZONE, "ok", "1.1.1.1");
    assertThat(data.get("status")).isEqualTo("ACTIVE");
    assertThat(data.get("is_online")).isEqualTo(true);
    assertThat(data.get("commission_pct")).isEqualTo(new BigDecimal("8.00"));
    assertThat(store.details.get(PID).status()).isEqualTo("ACTIVE");
    assertThat(audit.actions).contains("KYC_APPROVED");
    assertThat(outboxStore.all()).anyMatch(e -> e.type().contains("kyc_approved"));
  }

  @Test
  void complianceCannotApprove() {
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(
            () ->
                service.approve(
                    principal(AuthRole.ADMIN_COMPLIANCE),
                    PID,
                    new BigDecimal("8.00"),
                    ZONE,
                    null,
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void rejectWithCanReapplyFalse() {
    store.put(detail("KYC_SUBMITTED"));
    Map<String, Object> data =
        service.reject(ops(), PID, "Licence expired", "details", false, null);
    assertThat(data.get("status")).isEqualTo("REJECTED");
    assertThat(data.get("can_reapply")).isEqualTo(false);
    assertThat(store.details.get(PID).canReapply()).isFalse();
    assertThat(audit.actions).contains("KYC_REJECTED");
  }

  @Test
  void permanentSuspendBlocksReapply() {
    store.put(detail("ACTIVE"));
    Map<String, Object> data =
        service.suspend(ops(), PID, "Fraud", "PERMANENT", "internal", "10.0.0.1");
    assertThat(data.get("status")).isEqualTo("SUSPENDED");
    assertThat(data.get("is_online")).isEqualTo(false);
    assertThat(data.get("can_reapply")).isEqualTo(false);
    assertThat(audit.actions).contains("PHARMACY_SUSPENDED");
  }

  @Test
  void reactivateRequiresNotesAndWritesAudit() {
    store.put(detail("SUSPENDED"));
    Map<String, Object> data = service.reactivate(ops(), PID, "Issue resolved", null);
    assertThat(data.get("status")).isEqualTo("ACTIVE");
    assertThat(data.get("is_online")).isEqualTo(true);
    assertThat(store.details.get(PID).canReapply()).isTrue();
    assertThat(audit.actions).contains("PHARMACY_REACTIVATED");
    assertThat(audit.payloads.getLast().get("notes")).isEqualTo("Issue resolved");
  }

  @Test
  void reactivateOpsPreservesCanReapplyFalse_superRestores() {
    store.put(withCanReapply(detail("SUSPENDED"), false));
    service.reactivate(ops(), PID, "ops reopen", null);
    assertThat(store.details.get(PID).canReapply()).isFalse();

    store.put(withCanReapply(detail("SUSPENDED"), false));
    service.reactivate(principal(AuthRole.ADMIN_SUPER), PID, "super reopen", null);
    assertThat(store.details.get(PID).canReapply()).isTrue();
  }

  @Test
  void listKycSubmittedDefaultsToSubmittedAtAsc() {
    Instant older = NOW.minusSeconds(7200);
    Instant newer = NOW.minusSeconds(3600);
    store.listRows =
        List.of(
            listRow(PID, "KYC_SUBMITTED", older, older),
            listRow(Ids.newId(), "KYC_SUBMITTED", newer, newer));
    store.listTotal = 2;
    var result = service.list(ops(), "KYC_SUBMITTED", null, null, null, null, null, null, 1, 50);
    assertThat(store.lastFilter.sort()).isEqualTo("submitted_at");
    assertThat(store.lastFilter.order()).isEqualTo("asc");
    assertThat(result.meta().total()).isEqualTo(2);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pharmacies =
        (List<Map<String, Object>>) result.data().get("pharmacies");
    assertThat(pharmacies.getFirst().get("urgency")).isEqualTo("LOW");
  }

  @Test
  void requestDocumentsResetsSlaAndNotifies() {
    store.put(detail("KYC_SUBMITTED"));
    Map<String, Object> data =
        service.requestDocuments(
            principal(AuthRole.ADMIN_COMPLIANCE),
            PID,
            List.of("PAN_CARD", "BANK_STATEMENT"),
            "Please re-upload PAN",
            null);
    assertThat(data.get("kyc_sla_reset_at")).isEqualTo(NOW.toString());
    assertThat(data.get("notifications_sent")).isEqualTo(List.of("WHATSAPP", "EMAIL", "IN_APP"));
    assertThat(store.details.get(PID).kycSlaResetAt()).isEqualTo(NOW);
  }

  @Test
  void detailIncludesAutoKycAndDocumentsSummary() {
    store.put(detail("KYC_SUBMITTED"));
    store.docSummary = Map.of("GSTIN_CERTIFICATE", "VERIFIED", "PAN_CARD", "REJECTED");
    Map<String, Object> data = service.detail(ops(), PID);
    assertThat(data.get("code")).isEqualTo("PHM-0042");
    @SuppressWarnings("unchecked")
    Map<String, Object> kyc = (Map<String, Object>) data.get("kyc");
    assertThat(kyc.get("auto_kyc_status")).isEqualTo("PARTIAL");
    assertThat(kyc.get("documents_summary")).isEqualTo(store.docSummary);
  }

  @Test
  void approveErrors() {
    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("8"), ZONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_ACTIVE");
    store.put(detail("PENDING_KYC"));
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("8"), ZONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("KYC_NOT_SUBMITTED");
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("2"), ZONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_COMMISSION_PCT");
    assertThatThrownBy(
            () -> service.approve(ops(), PID, new BigDecimal("8"), Ids.newId(), null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_ZONE");
    zones.put(ZONE, new ZoneRecord(ZONE, "x", false));
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("8"), ZONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_ZONE");
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("8"), null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_ZONE");
  }

  @Test
  void rejectSuspendReactivateValidation() {
    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.reject(ops(), PID, "r", null, true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_ACTIVE");
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.reject(ops(), PID, " ", null, true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REJECTION_REASON_REQUIRED");
    assertThatThrownBy(() -> service.reject(ops(), PID, "r", null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    store.put(detail("SUSPENDED"));
    assertThatThrownBy(() -> service.suspend(ops(), PID, "r", "TEMPORARY", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_SUSPENDED");
    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.suspend(ops(), PID, "", "TEMPORARY", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> service.suspend(ops(), PID, "r", "NOPE", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.suspend(ops(), PID, "temp", "TEMPORARY", null, null);
    assertThat(store.details.get(PID).canReapply()).isTrue();

    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.reactivate(ops(), PID, "n", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOT_SUSPENDED");
    store.put(detail("SUSPENDED"));
    assertThatThrownBy(() -> service.reactivate(ops(), PID, " ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOTES_REQUIRED");
  }

  @Test
  void requestDocumentsValidationAndNotFound() {
    assertThatThrownBy(
            () -> service.requestDocuments(ops(), Ids.newId(), List.of("PAN_CARD"), "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.requestDocuments(ops(), PID, List.of("PAN_CARD"), "", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MESSAGE_REQUIRED");
    assertThatThrownBy(() -> service.requestDocuments(ops(), PID, List.of("NOPE"), "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DOCUMENT_TYPES");
    assertThatThrownBy(() -> service.requestDocuments(ops(), PID, List.of(), "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DOCUMENT_TYPES");
  }

  @Test
  void listValidationAndUrgency() {
    store.listRows =
        List.of(listRow(PID, "ACTIVE", NOW.minusSeconds(50 * 3600), NOW.minusSeconds(50 * 3600)));
    store.listTotal = 1;
    var high = service.list(ops(), "ACTIVE", null, null, null, null, "created_at", "desc", 1, 10);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) high.data().get("pharmacies");
    assertThat(rows.getFirst().get("urgency")).isEqualTo("HIGH");

    store.listRows =
        List.of(listRow(PID, "ACTIVE", NOW.minusSeconds(30 * 3600), NOW.minusSeconds(30 * 3600)));
    var med = service.list(ops(), "ALL", null, null, true, "Sharma", "business_name", "asc", 1, 10);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> medRows = (List<Map<String, Object>>) med.data().get("pharmacies");
    assertThat(medRows.getFirst().get("urgency")).isEqualTo("MEDIUM");

    assertThatThrownBy(() -> service.list(ops(), "NOPE", null, null, null, null, null, null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.list(ops(), "ALL", null, "GOLD", null, null, null, null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.list(ops(), "ALL", null, null, null, null, "bad", null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.list(ops(), "ALL", null, null, null, null, "created_at", "sideways", 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void roleGatesAndRateLimit() {
    assertThatThrownBy(() -> service.list(null, null, null, null, null, null, null, null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                service.list(
                    principal(AuthRole.ADMIN_FINANCE),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    store.put(detail("KYC_SUBMITTED"));
    service.detail(principal(AuthRole.ADMIN_FINANCE), PID);
    assertThatThrownBy(
            () ->
                service.requestDocuments(
                    principal(AuthRole.ADMIN_SUPPORT), PID, List.of("PAN_CARD"), "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(() -> service.list(ops(), null, null, null, null, null, null, null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void approveDefaultCommissionAndNotesTooLong() {
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(
            () -> service.approve(ops(), PID, new BigDecimal("8"), ZONE, "x".repeat(501), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> data = service.approve(ops(), PID, null, ZONE, null, null);
    assertThat(data.get("commission_pct")).isEqualTo(new BigDecimal("8.00"));
  }

  @Test
  void coversRemainingBranchesForJacoco() {
    // list: blank status/plan, page/limit clamps, support+compliance+super, explicit sort on queue
    store.listRows = List.of();
    store.listTotal = 0;
    service.list(principal(AuthRole.ADMIN_SUPPORT), "  ", null, "  ", null, null, null, null, 0, 0);
    service.list(
        principal(AuthRole.ADMIN_COMPLIANCE),
        "KYC_SUBMITTED",
        ZONE,
        "FREE",
        false,
        null,
        "created_at",
        "desc",
        null,
        999);
    service.list(
        principal(AuthRole.ADMIN_SUPER), "ALL", null, null, null, null, null, null, 1, null);

    // list row null anchors + null auto status
    store.listRows =
        List.of(
            new AdminListRow(
                PID, "PHM-1", "B", "O", "p", null, "ACTIVE", "FREE", true, null, null, null, null));
    store.listTotal = 1;
    var listed = service.list(ops(), "ACTIVE", null, null, null, null, "created_at", "asc", 1, 10);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.data().get("pharmacies");
    assertThat(rows.getFirst().get("document_age_hours")).isEqualTo(0L);
    assertThat(rows.getFirst().get("submitted_at")).isNull();
    assertThat(rows.getFirst().get("created_at")).isNull();

    // detail: null auto summary, null timestamps, support/super, not found
    when(autoKyc.latestAutoKycSummary(any())).thenReturn(null);
    store.put(
        new AdminDetailRow(
            PID,
            "PHM-0042",
            "Sharma",
            "Rajesh",
            "+91",
            "e@x.com",
            "PHARMACY",
            null,
            "g",
            "d",
            null,
            "p",
            "KYC_SUBMITTED",
            "FREE",
            new BigDecimal("8.00"),
            null,
            false,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
    Map<String, Object> detailData = service.detail(principal(AuthRole.ADMIN_SUPPORT), PID);
    @SuppressWarnings("unchecked")
    Map<String, Object> kyc = (Map<String, Object>) detailData.get("kyc");
    assertThat(kyc.get("auto_kyc_status")).isNull();
    assertThat(kyc.get("submitted_at")).isNull();
    assertThat(detailData.get("created_at")).isNull();
    service.detail(principal(AuthRole.ADMIN_SUPER), PID);
    assertThatThrownBy(() -> service.detail(ops(), Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
    assertThatThrownBy(() -> service.detail(principal(AuthRole.CUSTOMER), PID))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    // mutate validations + super decision path
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("21.00"), ZONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_COMMISSION_PCT");
    service.approve(
        principal(AuthRole.ADMIN_SUPER), PID, new BigDecimal("10.5"), ZONE, "n", "9.9.9.9");

    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.reject(ops(), PID, "r".repeat(201), null, true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reject(ops(), PID, "ok", "d".repeat(1001), true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.reject(ops(), PID, "bad licence", null, true, null);

    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.suspend(ops(), PID, "r".repeat(501), "TEMPORARY", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.suspend(ops(), PID, "r", null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.suspend(ops(), PID, "r", "TEMPORARY", "n".repeat(1001), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.suspend(ops(), PID, "hold", "TEMPORARY", "note", null);

    store.put(detail("SUSPENDED"));
    assertThatThrownBy(() -> service.reactivate(ops(), PID, "n".repeat(501), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.reactivate(principal(AuthRole.ADMIN_SUPER), PID, "ok", null);

    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(
            () -> service.requestDocuments(ops(), PID, List.of("PAN_CARD"), "m".repeat(1001), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.requestDocuments(
                    ops(), PID, java.util.Arrays.asList((String) null), "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DOCUMENT_TYPES");
    service.requestDocuments(
        principal(AuthRole.ADMIN_SUPER), PID, List.of("GSTIN_CERTIFICATE"), "please", null);

    assertThatThrownBy(
            () ->
                service.list(
                    principal(AuthRole.CUSTOMER), null, null, null, null, null, null, null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    // short-circuit null vs blank branches
    service.list(ops(), null, null, null, null, null, "  ", "  ", 1, 10);
    service.list(ops(), "KYC_SUBMITTED", null, null, null, null, null, "  ", 1, 10);
    service.list(ops(), "KYC_SUBMITTED", null, null, null, null, null, "desc", 1, 10);
    store.put(
        new AdminDetailRow(
            PID,
            "PHM-0042",
            "Sharma",
            "Rajesh",
            "+91",
            "e@x.com",
            "PHARMACY",
            Map.of(),
            "g",
            "d",
            null,
            "p",
            "KYC_SUBMITTED",
            "FREE",
            new BigDecimal("8.00"),
            ZONE,
            false,
            true,
            NOW,
            NOW,
            null,
            null,
            null,
            null,
            null,
            null));
    assertThat(service.detail(principal(AuthRole.ADMIN_COMPLIANCE), PID).get("zone_id"))
        .isEqualTo(ZONE.toString());
    service.detail(ops(), PID);

    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.reject(ops(), PID, null, null, true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REJECTION_REASON_REQUIRED");
    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.suspend(ops(), PID, null, "TEMPORARY", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REASON_REQUIRED");
    store.put(detail("SUSPENDED"));
    assertThatThrownBy(() -> service.reactivate(ops(), PID, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOTES_REQUIRED");
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.requestDocuments(ops(), PID, List.of("PAN_CARD"), null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MESSAGE_REQUIRED");
    assertThatThrownBy(() -> service.requestDocuments(ops(), PID, null, "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DOCUMENT_TYPES");
  }

  private static MedmatePrincipal ops() {
    return principal(AuthRole.ADMIN_OPERATIONS);
  }

  private static MedmatePrincipal principal(AuthRole role) {
    return new MedmatePrincipal(Ids.newId(), role, null, TokenScope.FULL, "j");
  }

  private static AdminDetailRow detail(String status) {
    return new AdminDetailRow(
        PID,
        "PHM-0042",
        "Sharma Medical",
        "Rajesh",
        "+919876543210",
        "r@s.com",
        "PHARMACY",
        Map.of("city", "Bengaluru", "pincode", "560034"),
        "29AABCS1429B1ZB",
        "KA/DL/1",
        null,
        "AABCS1429B",
        status,
        "FREE",
        new BigDecimal("8.00"),
        null,
        false,
        true,
        NOW.minusSeconds(3600),
        NOW.minusSeconds(86400),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static AdminDetailRow withCanReapply(AdminDetailRow row, boolean canReapply) {
    return new AdminDetailRow(
        row.pharmacyId(),
        row.code(),
        row.businessName(),
        row.ownerName(),
        row.phone(),
        row.email(),
        row.businessType(),
        row.address(),
        row.gstin(),
        row.drugLicenceNumber(),
        row.fssaiNumber(),
        row.panNumber(),
        row.status(),
        row.plan(),
        row.commissionPct(),
        row.zoneId(),
        row.online(),
        canReapply,
        row.kycSubmittedAt(),
        row.createdAt(),
        row.rejectionReason(),
        row.rejectionDetails(),
        row.activatedAt(),
        row.suspendedAt(),
        row.suspendType(),
        row.kycSlaResetAt());
  }

  private static AdminListRow listRow(
      UUID id, String status, Instant submitted, Instant ageAnchor) {
    return new AdminListRow(
        id,
        "PHM-0001",
        "Sharma",
        "Rajesh",
        "+9198",
        "Koramangala Zone",
        status,
        "FREE",
        false,
        submitted,
        NOW.minusSeconds(86400),
        ageAnchor,
        "PARTIAL");
  }

  static final class FakeAdminStore implements AdminPharmacyStore {
    final Map<UUID, AdminDetailRow> details = new ConcurrentHashMap<>();
    ListFilter lastFilter;
    List<AdminListRow> listRows = List.of();
    long listTotal;
    Map<String, String> docSummary = Map.of();

    void put(AdminDetailRow row) {
      details.put(row.pharmacyId(), row);
    }

    @Override
    public PageResult list(ListFilter filter) {
      lastFilter = filter;
      return new PageResult(listRows, listTotal);
    }

    @Override
    public Optional<AdminDetailRow> findDetail(UUID pharmacyId) {
      return Optional.ofNullable(details.get(pharmacyId));
    }

    @Override
    public Map<String, String> documentStatusSummary(UUID pharmacyId) {
      return docSummary;
    }

    @Override
    public String nextCode() {
      return "PHM-0099";
    }

    @Override
    public void approve(
        UUID pharmacyId,
        BigDecimal commissionPct,
        UUID zoneId,
        Instant activatedAt,
        Instant updatedAt) {
      AdminDetailRow cur = details.get(pharmacyId);
      details.put(
          pharmacyId,
          new AdminDetailRow(
              cur.pharmacyId(),
              cur.code(),
              cur.businessName(),
              cur.ownerName(),
              cur.phone(),
              cur.email(),
              cur.businessType(),
              cur.address(),
              cur.gstin(),
              cur.drugLicenceNumber(),
              cur.fssaiNumber(),
              cur.panNumber(),
              "ACTIVE",
              cur.plan(),
              commissionPct,
              zoneId,
              true,
              cur.canReapply(),
              cur.kycSubmittedAt(),
              cur.createdAt(),
              null,
              null,
              activatedAt,
              null,
              null,
              cur.kycSlaResetAt()));
    }

    @Override
    public void reject(
        UUID pharmacyId,
        String rejectionReason,
        String rejectionDetails,
        boolean canReapply,
        Instant rejectedAt) {
      AdminDetailRow cur = details.get(pharmacyId);
      details.put(
          pharmacyId,
          new AdminDetailRow(
              cur.pharmacyId(),
              cur.code(),
              cur.businessName(),
              cur.ownerName(),
              cur.phone(),
              cur.email(),
              cur.businessType(),
              cur.address(),
              cur.gstin(),
              cur.drugLicenceNumber(),
              cur.fssaiNumber(),
              cur.panNumber(),
              "REJECTED",
              cur.plan(),
              cur.commissionPct(),
              cur.zoneId(),
              false,
              canReapply,
              cur.kycSubmittedAt(),
              cur.createdAt(),
              rejectionReason,
              rejectionDetails,
              cur.activatedAt(),
              cur.suspendedAt(),
              cur.suspendType(),
              cur.kycSlaResetAt()));
    }

    @Override
    public void suspend(
        UUID pharmacyId, String suspendType, boolean canReapply, Instant suspendedAt) {
      AdminDetailRow cur = details.get(pharmacyId);
      details.put(
          pharmacyId,
          new AdminDetailRow(
              cur.pharmacyId(),
              cur.code(),
              cur.businessName(),
              cur.ownerName(),
              cur.phone(),
              cur.email(),
              cur.businessType(),
              cur.address(),
              cur.gstin(),
              cur.drugLicenceNumber(),
              cur.fssaiNumber(),
              cur.panNumber(),
              "SUSPENDED",
              cur.plan(),
              cur.commissionPct(),
              cur.zoneId(),
              false,
              canReapply,
              cur.kycSubmittedAt(),
              cur.createdAt(),
              cur.rejectionReason(),
              cur.rejectionDetails(),
              cur.activatedAt(),
              suspendedAt,
              suspendType,
              cur.kycSlaResetAt()));
    }

    @Override
    public void reactivate(UUID pharmacyId, Instant reactivatedAt, boolean canReapply) {
      AdminDetailRow cur = details.get(pharmacyId);
      details.put(
          pharmacyId,
          new AdminDetailRow(
              cur.pharmacyId(),
              cur.code(),
              cur.businessName(),
              cur.ownerName(),
              cur.phone(),
              cur.email(),
              cur.businessType(),
              cur.address(),
              cur.gstin(),
              cur.drugLicenceNumber(),
              cur.fssaiNumber(),
              cur.panNumber(),
              "ACTIVE",
              cur.plan(),
              cur.commissionPct(),
              cur.zoneId(),
              true,
              canReapply,
              cur.kycSubmittedAt(),
              cur.createdAt(),
              cur.rejectionReason(),
              cur.rejectionDetails(),
              reactivatedAt,
              null,
              null,
              cur.kycSlaResetAt()));
    }

    @Override
    public void resetKycSla(UUID pharmacyId, Instant slaResetAt) {
      AdminDetailRow cur = details.get(pharmacyId);
      details.put(
          pharmacyId,
          new AdminDetailRow(
              cur.pharmacyId(),
              cur.code(),
              cur.businessName(),
              cur.ownerName(),
              cur.phone(),
              cur.email(),
              cur.businessType(),
              cur.address(),
              cur.gstin(),
              cur.drugLicenceNumber(),
              cur.fssaiNumber(),
              cur.panNumber(),
              cur.status(),
              cur.plan(),
              cur.commissionPct(),
              cur.zoneId(),
              cur.online(),
              cur.canReapply(),
              cur.kycSubmittedAt(),
              cur.createdAt(),
              cur.rejectionReason(),
              cur.rejectionDetails(),
              cur.activatedAt(),
              cur.suspendedAt(),
              cur.suspendType(),
              slaResetAt));
    }
  }

  static final class FakeZones implements ZoneStore {
    final Map<UUID, ZoneRecord> byId = new LinkedHashMap<>();

    void put(UUID id, ZoneRecord z) {
      byId.put(id, z);
    }

    @Override
    public Optional<ZoneRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }
  }

  static final class FakeAudit implements AuditLogStore {
    final List<String> actions = new ArrayList<>();
    final List<Map<String, Object>> payloads = new ArrayList<>();

    @Override
    public void append(AuditLogRecord record) {
      actions.add(record.action());
      payloads.add(record.payload());
    }
  }
}
