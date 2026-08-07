package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.CategoryStore;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.PharmacyMasterHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.PharmacyMasterPage;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.SearchHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.SearchPage;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.StockOffer;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.SubstituteHit;
import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.MedicineRow;
import com.nammamedmate.catalogue.application.port.out.SearchCachePort;
import com.nammamedmate.catalogue.application.port.out.ZonePharmacyLookupPort;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MedicineSearchServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
  private static final UUID MED = UUID.randomUUID();
  private static final UUID CAT = UUID.randomUUID();
  private static final UUID PHARM = UUID.randomUUID();
  private static final UUID SUB = UUID.randomUUID();

  @Mock private MedicineSearchStore searchStore;
  @Mock private MedicineStore medicineStore;
  @Mock private CategoryStore categoryStore;
  @Mock private ZonePharmacyLookupPort pharmacies;
  @Mock private SearchCachePort cache;

  private MedicineSearchService service;
  private final MedmatePrincipal pharmacy =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARM, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new MedicineSearchService(
            searchStore,
            medicineStore,
            categoryStore,
            pharmacies,
            cache,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ObjectMapper());
    when(cache.getAutocomplete(anyString())).thenReturn(Optional.empty());
    when(cache.getMedicineDetail(any())).thenReturn(Optional.empty());
  }

  @Test
  void remainingBranches() {
    assertThat(new MedicineSearchService.Envelope(null, null).data()).isEmpty();

    SearchHit otc =
        new SearchHit(
            MED,
            "Crocin",
            "Para",
            "GSK",
            "Fever",
            "fever",
            "TABLET",
            BigDecimal.ONE,
            "TABLET",
            "OTC",
            false,
            100L,
            0.5);
    when(searchStore.search(eq("ot"), isNull(), eq("OTC"), isNull(), eq(true), eq(1), eq(20)))
        .thenReturn(new SearchPage(List.of(otc), 1));
    when(searchStore.bestOffers(anyList(), any(), any(), anyBoolean())).thenReturn(null);

    var env =
        service.search(
            null, "ot", null, "otc", null, null, null, null, null, "  ", false, false, false, 1, 20,
            "ip");
    assertThat(env.data().get("results")).asList().hasSize(1);

    when(searchStore.search(anyString(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(new SearchPage(List.of(), 0));
    when(searchStore.didYouMean(anyString())).thenReturn(Optional.empty());
    service.search(
        null, "ab", null, "H1", null, 1.0, null, null, null, null, false, false, false, 1, 20,
        "ip");
    service.search(
        null, "ab", null, null, null, null, 2.0, null, null, null, false, false, false, 1, 20,
        "ip");

    when(medicineStore.findById(MED))
        .thenReturn(
            Optional.of(
                new MedicineRow(
                    MED,
                    "Aug",
                    "Amox",
                    "GSK",
                    CAT,
                    "Antibiotics",
                    "TABLET",
                    BigDecimal.TEN,
                    "TABLET",
                    "H",
                    "30041090",
                    12,
                    100L,
                    null,
                    true,
                    false,
                    null,
                    0,
                    0,
                    List.of(SUB),
                    "d",
                    null,
                    NOW,
                    NOW)));
    when(searchStore.findSubstitutes(List.of(SUB)))
        .thenReturn(
            List.of(
                new SubstituteHit(
                    SUB, "Mox", "Amox", "Cipla", "TABLET", BigDecimal.TEN, "H", true, 19800L)));
    var subs = service.substitutes(MED, "ip");
    assertThat(subs.data().get("substitutes")).asList().hasSize(1);

    when(searchStore.searchMasterForPharmacy(eq(PHARM), eq("xx"), eq(false), eq(1), eq(20)))
        .thenReturn(
            new PharmacyMasterPage(
                List.of(
                    new PharmacyMasterHit(
                        MED,
                        "X",
                        "S",
                        "M",
                        "TABLET",
                        BigDecimal.ONE,
                        "OTC",
                        false,
                        100L,
                        null,
                        null,
                        null,
                        false,
                        false)),
                1));
    var masterOnly = service.pharmacySearch(pharmacy, "xx", "MASTER", null, 1, 20);
    assertThat(masterOnly.data().get("results")).asList().hasSize(1);

    doThrow(new RuntimeException("fail")).when(cache).putAutocomplete(anyString(), anyString());
    when(searchStore.autocomplete("zz", 10)).thenReturn(List.of());
    service.search(
        null, "zz", null, null, null, null, null, null, null, null, true, false, false, null, null,
        "ip");

    doThrow(new RuntimeException("fail")).when(cache).putMedicineDetail(any(), anyString());
    when(categoryStore.findById(CAT)).thenReturn(Optional.empty());
    when(searchStore.findSubstitutes(List.of(SUB))).thenReturn(List.of());
    when(searchStore.stockingOffers(MED, null, false)).thenReturn(List.of());
    service.getDetail(null, null, null, null, MED, false, "ip");

    assertThatThrownBy(() -> service.checkAvailability(null, List.of(MED), PHARM, "ip"))
        .isInstanceOf(AppException.class); // pharmacy mock empty

    when(pharmacies.findById(PHARM))
        .thenReturn(
            Optional.of(
                new ZonePharmacyLookupPort.PharmacyRef(PHARM, "S", null, true, false, "ACTIVE")));
    when(searchStore.checkAvailability(PHARM, List.of(MED)))
        .thenReturn(
            List.of(new MedicineSearchStore.AvailabilityHit(MED, "Aug", false, 0, null, false)));
    var avail = service.checkAvailability(null, List.of(MED), PHARM, "ip");
    assertThat(avail.data()).containsEntry("pharmacy_is_online", true);

    when(pharmacies.findById(PHARM))
        .thenReturn(
            Optional.of(
                new ZonePharmacyLookupPort.PharmacyRef(PHARM, "S", null, false, false, "ACTIVE")));
    var offline = service.checkAvailability(null, List.of(MED), PHARM, "ip");
    assertThat(offline.data()).containsEntry("pharmacy_is_online", false);

    assertThatThrownBy(
            () ->
                service.search(
                    null, null, null, null, null, null, null, null, null, null, false, false, false,
                    1, 20, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("QUERY_TOO_SHORT");

    assertThatThrownBy(() -> service.checkAvailability(null, null, PHARM, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_IDS_REQUIRED");

    when(searchStore.searchMasterForPharmacy(eq(PHARM), eq("yy"), eq(false), anyInt(), anyInt()))
        .thenReturn(new PharmacyMasterPage(List.of(), 0));
    service.pharmacySearch(pharmacy, "yy", "  ", false, 0, 0);
    service.pharmacySearch(pharmacy, "yy", null, false, 1, 20);

    when(searchStore.search(anyString(), any(), isNull(), any(), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(new SearchPage(List.of(), 0));
    when(searchStore.didYouMean(anyString())).thenReturn(Optional.empty());
    service.search(
        null, "ab", null, "   ", null, null, null, null, null, null, false, false, false, 1, 20,
        "ip");
  }

  @Test
  void indexBestNullOffersViaSearch() {
    SearchHit hit =
        new SearchHit(
            MED, "A", "S", "M", "C", "c", "TABLET", BigDecimal.ONE, "TABLET", "H", true, 100L, 1.0);
    when(searchStore.search(anyString(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(new SearchPage(List.of(hit), 1));
    when(searchStore.bestOffers(anyList(), any(), any(), anyBoolean()))
        .thenReturn(
            List.of(
                new StockOffer(MED, PHARM, "P", 50L, 1, true),
                new StockOffer(MED, PHARM, "P2", 40L, 1, true)));
    var env =
        service.search(
            null, "aa", null, "x", null, null, null, null, null, null, false, false, false, 1, 20,
            "ip");
    @SuppressWarnings("unchecked")
    Map<String, Object> first =
        (Map<String, Object>) ((List<?>) env.data().get("results")).getFirst();
    assertThat(first.get("best_pharmacy")).isNotNull();
  }
}
