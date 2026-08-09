package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.RackLocationStore;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.Kpi;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ListResult;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ListRow;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.UnlocatedPage;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.RackLocation;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RackLocationServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");
  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private static final UUID PRODUCT = UUID.fromString("11111111-2222-4333-8444-555555555555");

  @Mock private RackLocationStore store;
  @Mock private RateLimiter rateLimiter;

  private RackLocationService service;
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    service = new RackLocationService(store, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void pageResultNullMaps_andPaginationEdges() {
    RackLocationService.PageResult empty = new RackLocationService.PageResult(null, null);
    assertThat(empty.data()).isEmpty();
    assertThat(empty.meta()).isEmpty();

    RackLocation rack =
        new RackLocation(UUID.randomUUID(), PHARMACY, "A1-01", "Zone A", null, null, NOW, null);
    when(store.list(any()))
        .thenReturn(new ListResult(List.of(new ListRow(rack, 0, List.of())), 100));
    when(store.kpi(PHARMACY)).thenReturn(new Kpi(1, 1, 0, 0));

    RackLocationService.PageResult page = service.list(owner, null, null, 0, 0);
    assertThat(page.meta().get("page")).isEqualTo(1);
    assertThat(page.meta().get("limit")).isEqualTo(50);
    assertThat(page.meta().get("has_next")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> racks = (List<Map<String, Object>>) page.data().get("racks");
    assertThat(racks.get(0).get("created_at")).isNull();

    when(store.list(any())).thenReturn(new ListResult(List.of(), 0));
    when(store.kpi(PHARMACY)).thenReturn(new Kpi(0, 0, 0, 0));
    service.list(owner, null, null, null, null);
    service.list(owner, null, null, 1, 200);

    when(store.unlocated(eq(PHARMACY), eq(1), eq(20))).thenReturn(new UnlocatedPage(List.of(), 0));
    service.unlocated(owner, 0, 0);
    service.unlocated(owner, null, null);
    when(store.unlocated(eq(PHARMACY), eq(1), eq(20))).thenReturn(new UnlocatedPage(List.of(), 50));
    assertThat(service.unlocated(owner, 1, 20).meta().get("has_next")).isEqualTo(true);
    when(store.unlocated(eq(PHARMACY), eq(1), eq(100)))
        .thenReturn(new UnlocatedPage(List.of(), 50));
    assertThat(service.unlocated(owner, 1, 200).meta().get("has_next")).isEqualTo(false);
  }

  @Test
  void createNullZone_andBlankDescription() {
    assertThatThrownBy(() -> service.create(owner, "A1-01", null, "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.empty());
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> data = service.create(owner, "A1-01", "Zone A", "  ");
    assertThat(data.get("description")).isNull();
  }

  @Test
  void assignNullProductIds_printNullAndDupCodes_patchBlankAction() {
    assertThatThrownBy(() -> service.assign(owner, null, "A1-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMPTY_PRODUCT_LIST");

    assertThatThrownBy(() -> service.printLabels(owner, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMPTY_RACK_CODES");

    when(store.findByCodes(eq(PHARMACY), anyList())).thenReturn(List.of(sampleRack("A1-01")));
    when(store.medicineCount(PHARMACY, "A1-01")).thenReturn(0L);
    Map<String, Object> printed = service.printLabels(owner, List.of("A1-01", "A1-01"));
    assertThat(printed.get("label_count")).isEqualTo(1);

    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.of(sampleRack("A1-01")));
    when(store.addRackToProduct(PHARMACY, PRODUCT, "A1-01", NOW))
        .thenReturn(Optional.of(sampleProduct()));
    service.patchProductRack(owner, PRODUCT, "A1-01", "  ");

    PharmacyProduct noExpiry =
        new PharmacyProduct(
            PRODUCT,
            PHARMACY,
            null,
            "X",
            null,
            null,
            1,
            "t",
            null,
            null,
            "TABLET",
            "OTC",
            null,
            BigDecimal.ONE,
            100L,
            false,
            false,
            false,
            0,
            List.of("A1-01"),
            1,
            0,
            null,
            0L,
            null,
            null,
            NOW,
            NOW);
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.of(sampleRack("A1-01")));
    when(store.medicinesInRack(PHARMACY, "A1-01")).thenReturn(List.of(noExpiry));
    Map<String, Object> detail = service.detail(owner, "A1-01");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> meds = (List<Map<String, Object>>) detail.get("medicines");
    assertThat(meds.get(0).get("earliest_expiry")).isNull();
  }

  @Test
  void normalizeNull_andPortCompactConstructors() {
    assertThatThrownBy(() -> RackLocationService.normalizeCode(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RACK_CODE_FORMAT");

    assertThat(new ListRow(sampleRack("A1-01"), 0, null).preview()).isEmpty();
    assertThat(new ListResult(null, 0).rows()).isEmpty();
    assertThat(new UnlocatedPage(null, 0).products()).isEmpty();
  }

  private static RackLocation sampleRack(String code) {
    return new RackLocation(UUID.randomUUID(), PHARMACY, code, "Zone A", "d", NOW, NOW, null);
  }

  private static PharmacyProduct sampleProduct() {
    return new PharmacyProduct(
        PRODUCT,
        PHARMACY,
        null,
        "X",
        null,
        null,
        1,
        "t",
        null,
        null,
        "TABLET",
        "OTC",
        null,
        BigDecimal.ONE,
        100L,
        false,
        false,
        false,
        0,
        List.of("A1-01"),
        1,
        0,
        null,
        0L,
        null,
        null,
        NOW,
        NOW);
  }
}
