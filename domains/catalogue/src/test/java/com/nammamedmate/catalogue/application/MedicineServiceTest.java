package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.MedicineService.CreateCommand;
import com.nammamedmate.catalogue.application.MedicineService.PageResult;
import com.nammamedmate.catalogue.application.MedicineService.UpdateCommand;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.catalogue.application.port.out.BanMappingHidePort;
import com.nammamedmate.catalogue.application.port.out.MedicineBanJobStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminListFilter;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminMappingRow;
import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.ListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.MedicineRow;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.SubstituteRef;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.SummaryStats;
import com.nammamedmate.catalogue.application.port.out.OrderDemandPort;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
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
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class MedicineServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  @Mock private MedicineStore store;
  @Mock private AuditLogStore auditLog;
  @Mock private BanMappingHidePort banHide;
  @Mock private MedicineBanJobStore banJobs;
  @Mock private MedicineMappingStore mappings;
  @Mock private OrderDemandPort orderDemand;

  private MedicineService service;

  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal compliance =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new MedicineService(
            store,
            auditLog,
            banHide,
            banJobs,
            mappings,
            orderDemand,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac_createForcesRxForScheduleH_andWritesAudit() {
    when(store.categoryActive(CATEGORY)).thenReturn(true);
    when(store.hsnExists("30041090")).thenReturn(true);

    Map<String, Object> data =
        service.create(
            superAdmin,
            new CreateCommand(
                "Augmentin 625 Tablet",
                "Amoxicillin (500mg) + Clavulanic Acid (125mg)",
                "GSK India",
                CATEGORY,
                "TABLET",
                10,
                "TABLET",
                "H",
                "30041090",
                12,
                218.50,
                false,
                "desc",
                List.of(),
                999));

    assertThat(data).containsEntry("is_rx_only", true).containsEntry("monthly_demand", 0);
    assertThat((BigDecimal) data.get("mrp")).isEqualByComparingTo("218.50");
    ArgumentCaptor<AuditLogRecord> audit = ArgumentCaptor.forClass(AuditLogRecord.class);
    verify(auditLog).append(audit.capture());
    assertThat(audit.getValue().action()).isEqualTo("MEDICINE_CREATED");
    assertThat(audit.getValue().entityType()).isEqualTo("MEDICINE");
  }

  @Test
  void ac_duplicateMedicineReturns409() {
    when(store.categoryActive(CATEGORY)).thenReturn(true);
    when(store.hsnExists("30041090")).thenReturn(true);
    doThrow(new DuplicateKeyException("uq")).when(store).insert(any());

    assertThatThrownBy(() -> service.create(ops, validCreate()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DUPLICATE_MEDICINE");
  }

  @Test
  void complianceCannotCreateMedicines() {
    assertThatThrownBy(() -> service.create(compliance, validCreate()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.create(null, validCreate()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void nonAdminCannotListMedicines() {
    assertThatThrownBy(
            () -> service.list(customer, null, null, null, null, false, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void complianceMayOnlyUpdateSchedule() {
    UUID id = UUID.randomUUID();
    when(store.findById(id)).thenReturn(Optional.of(row(id, "H", false, null, null)));

    assertThatThrownBy(
            () ->
                service.update(
                    compliance,
                    id,
                    new UpdateCommand("New Name", null, null, null, null, null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.update(
                    compliance,
                    id,
                    new UpdateCommand(null, "desc", null, null, null, null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.update(
                    compliance,
                    id,
                    new UpdateCommand(null, null, CATEGORY, null, null, null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.update(
                    compliance,
                    id,
                    new UpdateCommand(null, null, null, null, 12, null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.update(
                    compliance,
                    id,
                    new UpdateCommand(null, null, null, null, null, 10.0, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.update(
                    compliance,
                    id,
                    new UpdateCommand(null, null, null, null, null, null, true, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.update(
                    compliance,
                    id,
                    new UpdateCommand(null, null, null, null, null, null, null, List.of(), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.update(
                    compliance,
                    id,
                    new UpdateCommand(null, null, null, null, null, null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.update(
                    null,
                    id,
                    new UpdateCommand(null, null, null, "H1", null, null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                service.update(
                    customer,
                    id,
                    new UpdateCommand(null, null, null, "H1", null, null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    Map<String, Object> data =
        service.update(
            compliance,
            id,
            new UpdateCommand(null, null, null, "H1", null, null, null, null, null));
    assertThat(data.get("updated_fields")).asList().contains("schedule");
  }

  @Test
  void ac_scheduleXNotAvailableOnline() {
    UUID id = UUID.randomUUID();
    when(store.findById(id)).thenReturn(Optional.of(row(id, "X", false, null, null)));

    assertThatThrownBy(() -> service.assertOnlineStorefrontAllowed(id))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SCHEDULE_X_NOT_AVAILABLE_ONLINE");
  }

  @Test
  void ac_banHidesMappingsAndExcludesFromDefaultList() {
    UUID id = UUID.randomUUID();
    when(store.findById(id)).thenReturn(Optional.of(row(id, "H", false, null, null)));
    when(banHide.hideAllForMedicine(id)).thenReturn(3);

    Map<String, Object> data = service.ban(compliance, id, "CDSCO ban");

    assertThat(data)
        .containsEntry("is_banned", true)
        .containsEntry("pharmacy_mappings_hidden", 3)
        .containsKey("storefront_removal_job_id")
        .containsEntry("storefront_removal_job_status", "COMPLETED");
    verify(store).setBanned(eq(id), eq(true), eq("CDSCO ban"), eq(NOW));
    verify(banJobs).insertQueued(any(), eq(id), eq("CDSCO ban"), eq(compliance.subject()), eq(NOW));
    verify(banJobs).markRunning(any(), eq(NOW));
    verify(banJobs).markCompleted(any(), eq(3), eq(NOW));
    verify(auditLog).append(any());
  }

  @Test
  void ac_getDetailIncludesStockingSubstitutesDemandCeiling() {
    UUID id = UUID.randomUUID();
    UUID sub = UUID.randomUUID();
    UUID pharmacyId = UUID.randomUUID();
    UUID mappingId = UUID.randomUUID();
    MedicineRow medicine =
        new MedicineRow(
            id,
            "Augmentin",
            "Amox",
            "GSK",
            CATEGORY,
            "Antibiotics",
            "TABLET",
            new BigDecimal("10.00"),
            "TABLET",
            "H",
            "30041090",
            12,
            21850L,
            null,
            true,
            false,
            null,
            10,
            2,
            List.of(sub),
            "desc",
            superAdmin.subject(),
            NOW,
            NOW);
    when(store.findById(id)).thenReturn(Optional.of(medicine));
    when(store.findSubstituteRefs(List.of(sub)))
        .thenReturn(List.of(new SubstituteRef(sub, "Mox CV", "Cipla")));
    when(mappings.listForAdmin(any()))
        .thenReturn(
            new AdminListResult(
                List.of(
                    new AdminMappingRow(
                        mappingId,
                        pharmacyId,
                        "City Medicals",
                        "Zone A",
                        21500L,
                        4,
                        true,
                        false,
                        NOW)),
                1,
                1));

    Map<String, Object> data = service.get(ops, id);

    assertThat(data).containsEntry("mrp_ceiling", null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> stocking =
        (List<Map<String, Object>>) data.get("stocking_pharmacies");
    assertThat(stocking).hasSize(1);
    assertThat(stocking.getFirst())
        .containsEntry("pharmacy_id", pharmacyId.toString())
        .containsEntry("pharmacy_name", "City Medicals")
        .containsEntry("stock_quantity", 4)
        .containsEntry("is_visible", true);
    assertThat(data.get("substitutes")).asList().hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> demand = (Map<String, Object>) data.get("demand_stats");
    assertThat(demand).containsEntry("monthly_demand_trend", "STABLE");
    ArgumentCaptor<AdminListFilter> filter = ArgumentCaptor.forClass(AdminListFilter.class);
    verify(mappings).listForAdmin(filter.capture());
    assertThat(filter.getValue().masterMedicineId()).isEqualTo(id);
  }

  @Test
  void ac_invalidGstOnPatch() {
    UUID id = UUID.randomUUID();
    when(store.findById(id)).thenReturn(Optional.of(row(id, "OTC", false, null, null)));

    assertThatThrownBy(
            () ->
                service.update(
                    ops, id, new UpdateCommand(null, null, null, null, 7, null, null, null, null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_GST_RATE");
  }

  @Test
  void ac_monthlyDemandIgnoredOnCreate() {
    when(store.categoryActive(CATEGORY)).thenReturn(true);
    when(store.hsnExists("30041090")).thenReturn(true);

    Map<String, Object> data = service.create(ops, validCreate());
    assertThat(data).containsEntry("monthly_demand", 0);
    ArgumentCaptor<MedicineRow> captor = ArgumentCaptor.forClass(MedicineRow.class);
    verify(store).insert(captor.capture());
    assertThat(captor.getValue().monthlyDemand()).isZero();
  }

  @Test
  void ac_listBannedOnlyIncludesBanReason() {
    UUID id = UUID.randomUUID();
    when(store.list(any()))
        .thenReturn(new ListResult(List.of(row(id, "H", true, "CDSCO", 100L)), 1));

    PageResult page = service.list(ops, null, null, null, null, true, null, "name", "asc", 1, 20);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> medicines = (List<Map<String, Object>>) page.data().get("medicines");
    assertThat(medicines.get(0))
        .containsEntry("ban_reason", "CDSCO")
        .containsEntry("is_banned", true);
    assertThat(page.meta().hasNext()).isFalse();
  }

  @Test
  void summary_andListFilters() {
    when(store.summary(NOW)).thenReturn(new SummaryStats(1, 1, 1, 0, 0, 1, 0, 0, 21850L, 0, NOW));
    Map<String, Object> summary = service.summary(ops);
    assertThat((BigDecimal) summary.get("avg_mrp")).isEqualByComparingTo("218.50");

    when(store.list(any())).thenReturn(new ListResult(List.of(), 0));
    service.list(ops, CATEGORY, "H", 12, true, false, "aug", "mrp", "desc", 0, 200);
    service.list(ops, null, null, null, null, null, null, "bad", "bad", null, null);
  }

  @Test
  void update_ban_unban_andErrors() {
    UUID id = UUID.randomUUID();
    when(store.findById(id)).thenReturn(Optional.of(row(id, "OTC", false, null, 50000L)));
    when(store.categoryActive(CATEGORY)).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.update(
                    ops,
                    id,
                    new UpdateCommand(null, null, null, null, null, 100.00, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MRP_BELOW_CEILING");

    when(store.findById(id)).thenReturn(Optional.of(row(id, "OTC", true, "ban", null)));
    assertThatThrownBy(
            () ->
                service.update(
                    ops,
                    id,
                    new UpdateCommand("New", null, null, null, null, null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_IS_BANNED");

    when(store.findById(id)).thenReturn(Optional.of(row(id, "OTC", false, null, null)));
    Map<String, Object> updated =
        service.update(
            ops,
            id,
            new UpdateCommand(
                "New Name", "new desc", CATEGORY, "H", 18, 300.00, false, List.of(), 42));
    assertThat(updated.get("updated_fields")).asList().isNotEmpty();

    when(store.findById(id)).thenReturn(Optional.of(row(id, "H", false, null, null)));
    when(banHide.hideAllForMedicine(id)).thenReturn(0);
    service.ban(superAdmin, id, "reason");
    assertThatThrownBy(() -> service.ban(ops, id, "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(store.findById(id)).thenReturn(Optional.of(row(id, "H", true, "reason", null)));
    assertThatThrownBy(() -> service.ban(superAdmin, id, "again"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_BANNED");

    Map<String, Object> unbanned = service.unban(compliance, id, "lifted");
    assertThat(unbanned).containsEntry("is_banned", false);
    assertThatThrownBy(() -> service.unban(superAdmin, id, " "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
  }

  @Test
  void validationErrors_andRefreshDemand() {
    assertThatThrownBy(() -> service.create(customer, validCreate()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () -> service.list(null, null, null, null, null, null, null, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () -> service.list(ops, null, "Z", null, null, null, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SCHEDULE");
    assertThatThrownBy(() -> service.list(ops, null, null, 7, null, null, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_GST_RATE");

    when(store.categoryActive(CATEGORY)).thenReturn(false);
    assertThatThrownBy(() -> service.create(ops, validCreate()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CATEGORY");

    when(store.categoryActive(CATEGORY)).thenReturn(true);
    when(store.hsnExists(anyString())).thenReturn(false);
    assertThatThrownBy(() -> service.create(ops, validCreate()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_HSN_CODE");

    when(store.hsnExists("30041090")).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CreateCommand(
                        "Bad Form Med",
                        "salt",
                        "mfr",
                        CATEGORY,
                        "BAD",
                        1,
                        "TABLET",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        false,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FORM");

    UUID missing = UUID.randomUUID();
    when(store.categoryActive(CATEGORY)).thenReturn(true);
    when(store.countExistingIds(List.of(missing))).thenReturn(0);
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    new CreateCommand(
                        "Augmentin",
                        "salt",
                        "mfr",
                        CATEGORY,
                        "TABLET",
                        1,
                        "TABLET",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        false,
                        null,
                        List.of(missing),
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SUBSTITUTE_ID");

    UUID id = UUID.randomUUID();
    when(store.listAllIds()).thenReturn(List.of(id));
    when(orderDemand.trailing30DayOrderCount(id)).thenReturn(5);
    service.refreshMonthlyDemand();
    verify(store).updateMonthlyDemand(id, 5, NOW);

    when(store.findById(id)).thenReturn(Optional.of(row(id, "H", false, null, null)));
    service.assertOnlineStorefrontAllowed(id);
    when(store.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.assertOnlineStorefrontAllowed(id))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");
  }

  @Test
  void parsePositiveAmountPaise_andPaiseToRupees() {
    assertThat(MedicineService.parsePositiveAmountPaise("12.50")).isEqualTo(1250L);
    assertThat(MedicineService.parsePositiveAmountPaise(new BigDecimal("1.00"))).isEqualTo(100L);
    assertThat(MedicineService.parsePositiveAmountPaise(1)).isEqualTo(100L);
    assertThat(MedicineService.paiseToRupees(21850)).isEqualByComparingTo("218.50");
    assertThatThrownBy(() -> MedicineService.parsePositiveAmountPaise(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> MedicineService.parsePositiveAmountPaise("abc"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> MedicineService.parsePositiveAmountPaise(Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> MedicineService.parsePositiveAmountPaise(new BigDecimal("1.001")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> MedicineService.parsePositiveAmountPaise(0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  private CreateCommand validCreate() {
    return new CreateCommand(
        "Augmentin 625 Tablet",
        "Amoxicillin (500mg) + Clavulanic Acid (125mg)",
        "GSK India",
        CATEGORY,
        "TABLET",
        10,
        "TABLET",
        "H",
        "30041090",
        12,
        218.50,
        false,
        "desc",
        List.of(),
        999);
  }

  private MedicineRow row(
      UUID id, String schedule, boolean banned, String banReason, Long ceiling) {
    return new MedicineRow(
        id,
        "Med",
        "Salt",
        "Mfr",
        CATEGORY,
        "Antibiotics",
        "TABLET",
        new BigDecimal("10.00"),
        "TABLET",
        schedule,
        "30041090",
        12,
        21850L,
        ceiling,
        forcesRx(schedule),
        banned,
        banReason,
        0,
        0,
        List.of(),
        "d",
        superAdmin.subject(),
        NOW,
        NOW);
  }

  private static boolean forcesRx(String schedule) {
    return "H".equals(schedule) || "H1".equals(schedule) || "X".equals(schedule);
  }
}
