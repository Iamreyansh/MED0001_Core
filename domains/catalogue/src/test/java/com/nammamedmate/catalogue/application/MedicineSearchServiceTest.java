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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.CategoryStore;
import com.nammamedmate.catalogue.application.port.out.CategoryStore.CategoryRow;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.AutocompleteHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.AvailabilityHit;
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
import com.nammamedmate.catalogue.application.port.out.ZonePharmacyLookupPort.PharmacyRef;
import com.nammamedmate.kernel.api.PaginationMeta;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MedicineSearchServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
  private static final UUID MED = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CAT = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID PHARM = UUID.fromString("aaaaaaaa-0009-0009-0009-000000000001");
  private static final UUID ZONE = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID SUB = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Mock private MedicineSearchStore searchStore;
  @Mock private MedicineStore medicineStore;
  @Mock private CategoryStore categoryStore;
  @Mock private ZonePharmacyLookupPort pharmacies;
  @Mock private SearchCachePort cache;

  private MedicineSearchService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal pharmacy =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

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
  void search_returnsBestPharmacyAndScheduleXNote() {
    SearchHit hit =
        new SearchHit(
            MED,
            "Augmentin",
            "Amox",
            "GSK",
            "Antibiotics",
            "antibiotics",
            "TABLET",
            new BigDecimal("10"),
            "TABLET",
            "X",
            true,
            21850L,
            0.98);
    when(searchStore.search(eq("aug"), isNull(), isNull(), isNull(), eq(true), eq(1), eq(20)))
        .thenReturn(new SearchPage(List.of(hit), 1));
    when(searchStore.bestOffers(eq(List.of(MED)), isNull(), isNull(), eq(false)))
        .thenReturn(List.of(new StockOffer(MED, PHARM, "Shop", 21500L, 5, true)));

    var env =
        service.search(
            null, "aug", null, null, null, 12.9, 77.6, null, null, null, false, false, false, 1, 20,
            "1.1.1.1");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) env.data().get("results");
    assertThat(results.getFirst()).containsEntry("available_online", false);
    assertThat(results.getFirst().get("note")).asString().contains("Schedule X");
    assertThat(results.getFirst().get("best_pharmacy")).isInstanceOf(Map.class);
    assertThat(env.meta()).isInstanceOf(PaginationMeta.class);
  }

  @Test
  void search_includeBannedOnlyForAdmin() {
    when(searchStore.search(eq("ab"), isNull(), isNull(), isNull(), eq(true), eq(1), eq(20)))
        .thenReturn(new SearchPage(List.of(), 0));
    when(searchStore.bestOffers(anyList(), any(), any(), anyBoolean())).thenReturn(List.of());
    when(searchStore.didYouMean(anyString())).thenReturn(Optional.empty());

    service.search(
        null, "ab", null, null, null, null, null, null, null, null, false, false, true, 1, 20,
        "ip");
    service.search(
        customer, "ab", null, null, null, null, null, null, null, null, false, false, true, 1, 20,
        "ip");
    verify(searchStore, org.mockito.Mockito.atLeastOnce())
        .search("ab", null, null, null, true, 1, 20);

    when(searchStore.search(eq("ab"), isNull(), isNull(), isNull(), eq(false), eq(1), eq(20)))
        .thenReturn(new SearchPage(List.of(), 0));
    service.search(
        admin, "ab", null, null, null, null, null, null, null, null, false, false, true, 1, 20,
        "ip");
    MedmatePrincipal ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    MedmatePrincipal compliance =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    service.search(
        ops, "ab", null, null, null, null, null, null, null, null, false, false, true, 1, 20, "ip");
    service.search(
        compliance,
        "ab",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        true,
        1,
        20,
        "ip");
    verify(searchStore, org.mockito.Mockito.atLeastOnce())
        .search("ab", null, null, null, false, 1, 20);
  }

  @Test
  void search_showOosPassedToBestOffers() {
    when(searchStore.search(anyString(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(new SearchPage(List.of(), 0));
    when(searchStore.bestOffers(anyList(), isNull(), isNull(), eq(true))).thenReturn(List.of());
    when(searchStore.didYouMean(anyString())).thenReturn(Optional.empty());

    service.search(
        null, "ab", null, null, null, null, null, null, null, null, false, true, false, 1, 20,
        "ip");

    verify(searchStore).bestOffers(List.of(), null, null, true);
  }

  @Test
  void search_didYouMeanOnEmpty() {
    when(searchStore.search(anyString(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(new SearchPage(List.of(), 0));
    when(searchStore.didYouMean("xyzzy")).thenReturn(Optional.of("crocin"));
    when(searchStore.bestOffers(anyList(), any(), any(), anyBoolean())).thenReturn(List.of());

    var env =
        service.search(
            customer, "xyzzy", null, null, null, null, null, null, null, null, false, false, false,
            null, null, "ip");

    assertThat(env.data()).containsEntry("did_you_mean", "crocin");
  }

  @Test
  void search_zoneFromPincodeAndPharmacyFilter() {
    when(searchStore.search(anyString(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(new SearchPage(List.of(), 0));
    when(pharmacies.zoneIdForPincode("560001")).thenReturn(Optional.of(ZONE));
    when(searchStore.bestOffers(anyList(), eq(ZONE), eq(PHARM), eq(false))).thenReturn(List.of());
    when(searchStore.didYouMean(anyString())).thenReturn(Optional.empty());

    service.search(
        null, "ab", CAT, "H", true, null, null, PHARM, null, "560001", false, false, false, 1, 50,
        null);

    verify(searchStore).bestOffers(List.of(), ZONE, PHARM, false);
  }

  @Test
  void search_explicitZoneIdWins() {
    when(searchStore.search(anyString(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(new SearchPage(List.of(), 0));
    when(searchStore.bestOffers(anyList(), eq(ZONE), isNull(), eq(false))).thenReturn(List.of());
    when(searchStore.didYouMean(anyString())).thenReturn(Optional.empty());

    service.search(
        null, "ab", null, "BAD", null, null, null, null, ZONE, "560001", false, false, false, 0, 0,
        " ");

    verify(pharmacies, never()).zoneIdForPincode(anyString());
  }

  @Test
  void autocomplete_cacheHitAndMiss() {
    when(searchStore.autocomplete("aug", 10))
        .thenReturn(List.of(new AutocompleteHit(MED, "Augmentin", "GSK")));

    var miss =
        service.search(
            null, "aug", null, null, null, null, null, null, null, null, true, false, false, null,
            null, "ip");
    assertThat(miss.meta()).isEqualTo(Map.of("cached", false));
    verify(cache).putAutocomplete(eq("aug"), anyString());

    when(cache.getAutocomplete("aug"))
        .thenReturn(
            Optional.of(
                "[{\"medicine_id\":\""
                    + MED
                    + "\",\"name\":\"Augmentin\",\"manufacturer\":\"GSK\"}]"));
    var hit =
        service.search(
            null, "aug", null, null, null, null, null, null, null, null, true, false, false, null,
            null, "ip");
    assertThat(hit.meta()).isEqualTo(Map.of("cached", true));
  }

  @Test
  void queryValidation() {
    assertThatThrownBy(
            () ->
                service.search(
                    null, "a", null, null, null, null, null, null, null, null, false, false, false,
                    1, 20, "ip"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("QUERY_TOO_SHORT");
    assertThatThrownBy(
            () ->
                service.search(
                    null, " ", null, null, null, null, null, null, null, null, false, false, false,
                    1, 20, "ip"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.search(
                    null,
                    "x".repeat(201),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    1,
                    20,
                    "ip"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("QUERY_TOO_LONG");
  }

  @Test
  void detail_bannedAndNotFoundAndHappy() {
    assertThatThrownBy(() -> service.getDetail(null, null, null, null, null, false, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");
    when(medicineStore.findById(MED)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getDetail(null, null, null, null, MED, false, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    when(medicineStore.findById(MED)).thenReturn(Optional.of(row(true, "H", List.of())));
    assertThatThrownBy(() -> service.getDetail(null, null, null, null, MED, false, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_BANNED");

    when(medicineStore.findById(MED)).thenReturn(Optional.of(row(false, "X", List.of(SUB))));
    when(categoryStore.findById(CAT))
        .thenReturn(
            Optional.of(
                new CategoryRow(
                    CAT, "Antibiotics", "antibiotics", null, true, 1, null, NOW, NOW, 1)));
    when(searchStore.findSubstitutes(List.of(SUB)))
        .thenReturn(
            List.of(
                new SubstituteHit(
                    SUB,
                    "Mox",
                    "Amox",
                    "Cipla",
                    "TABLET",
                    new BigDecimal("10"),
                    "H",
                    true,
                    19800L)));
    when(searchStore.stockingOffers(MED, ZONE, false))
        .thenReturn(List.of(new StockOffer(MED, PHARM, "Shop", 20000L, 2, true)));
    when(pharmacies.zoneIdForPincode("560001")).thenReturn(Optional.of(ZONE));

    var env = service.getDetail(12.9, 77.6, null, "560001", MED, false, "ip");
    assertThat(env.data()).containsEntry("available_online", false);
    assertThat(env.data().get("substitutes")).asList().hasSize(1);
    assertThat(env.data().get("stocking_pharmacies_nearby")).asList().hasSize(1);
    verify(cache).putMedicineDetail(eq(MED), anyString());
  }

  @Test
  void detail_usesCache() {
    when(medicineStore.findById(MED)).thenReturn(Optional.of(row(false, "H", List.of())));
    when(cache.getMedicineDetail(MED))
        .thenReturn(
            Optional.of(
                "{\"medicine_id\":\"" + MED + "\",\"name\":\"Cached\",\"substitutes\":[]}"));
    when(searchStore.stockingOffers(MED, null, false)).thenReturn(List.of());

    var env = service.getDetail(null, null, null, null, MED, false, "ip");
    assertThat(env.data()).containsEntry("name", "Cached");
    verify(searchStore, never()).findSubstitutes(anyList());

    when(searchStore.stockingOffers(MED, null, true)).thenReturn(List.of());
    service.getDetail(null, null, null, null, MED, true, "ip");
    verify(searchStore).stockingOffers(MED, null, true);
  }

  @Test
  void substitutes_andAvailability() {
    when(medicineStore.findById(MED)).thenReturn(Optional.of(row(false, "H", List.of(SUB))));
    when(searchStore.findSubstitutes(List.of(SUB))).thenReturn(List.of());
    assertThat(service.substitutes(MED, "ip").data()).containsEntry("medicine_name", "Augmentin");

    when(medicineStore.findById(SUB)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.substitutes(SUB, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    assertThatThrownBy(() -> service.checkAvailability(null, List.of(MED), null, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
    assertThatThrownBy(() -> service.checkAvailability(null, List.of(), PHARM, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_IDS_REQUIRED");
    List<UUID> tooMany = IntStream.range(0, 51).mapToObj(i -> UUID.randomUUID()).toList();
    assertThatThrownBy(() -> service.checkAvailability(customer, tooMany, PHARM, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TOO_MANY_MEDICINES");

    when(pharmacies.findById(PHARM)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.checkAvailability(null, List.of(MED), PHARM, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");

    when(pharmacies.findById(PHARM))
        .thenReturn(Optional.of(new PharmacyRef(PHARM, "Shop", ZONE, true, false, "SUSPENDED")));
    assertThatThrownBy(() -> service.checkAvailability(null, List.of(MED), PHARM, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");

    when(pharmacies.findById(PHARM))
        .thenReturn(Optional.of(new PharmacyRef(PHARM, "Shop", ZONE, true, true, "ACTIVE")));
    when(searchStore.checkAvailability(PHARM, List.of(MED)))
        .thenReturn(List.of(new AvailabilityHit(MED, "Augmentin", true, 4, 21500L, true)));

    var env = service.checkAvailability(null, List.of(MED), PHARM, "ip");
    assertThat(env.data()).containsEntry("pharmacy_is_online", false);
    assertThat(env.data()).containsEntry("checked_at", NOW.toString());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) env.data().get("results");
    assertThat(results.getFirst()).containsEntry("pharmacy_price", new BigDecimal("215.00"));
  }

  @Test
  void pharmacySearch_masterAndCustom() {
    when(searchStore.searchMasterForPharmacy(eq(PHARM), eq("crocin"), eq(false), eq(1), eq(20)))
        .thenReturn(
            new PharmacyMasterPage(
                List.of(
                    new PharmacyMasterHit(
                        MED,
                        "Crocin",
                        "Para",
                        "GSK",
                        "TABLET",
                        new BigDecimal("20"),
                        "OTC",
                        false,
                        2250L,
                        2100L,
                        10,
                        UUID.randomUUID(),
                        true,
                        true)),
                1));

    var all = service.pharmacySearch(pharmacy, "crocin", "ALL", false, 1, 20);
    assertThat(all.data().get("results")).asList().hasSize(1);

    var custom = service.pharmacySearch(pharmacy, "crocin", "CUSTOM", true, null, null);
    assertThat(custom.data().get("results")).asList().isEmpty();
    verify(searchStore, never())
        .searchMasterForPharmacy(any(), eq("crocin"), eq(true), anyInt(), anyInt());

    var badSource = service.pharmacySearch(pharmacy, "crocin", "NOPE", null, 1, 20);
    assertThat(badSource.data().get("results")).asList().hasSize(1);

    assertThatThrownBy(() -> service.pharmacySearch(null, "ab", "ALL", false, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                service.pharmacySearch(
                    new MedmatePrincipal(
                        UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    "ab",
                    "ALL",
                    false,
                    1,
                    20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.pharmacySearch(
                    new MedmatePrincipal(
                        UUID.randomUUID(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j"),
                    "ab",
                    "ALL",
                    false,
                    1,
                    20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void cacheCorruptAndHelpers() {
    when(cache.getAutocomplete("aug")).thenReturn(Optional.of("not-json"));
    when(searchStore.autocomplete("aug", 10)).thenReturn(List.of());
    var env =
        service.search(
            null, "aug", null, null, null, null, null, null, null, null, true, false, false, null,
            null, "ip");
    assertThat(env.meta()).isEqualTo(Map.of("cached", false));

    when(medicineStore.findById(MED)).thenReturn(Optional.of(row(false, "H", List.of())));
    when(cache.getMedicineDetail(MED)).thenReturn(Optional.of("{bad"));
    when(categoryStore.findById(CAT)).thenReturn(Optional.empty());
    when(searchStore.findSubstitutes(List.of())).thenReturn(List.of());
    when(searchStore.stockingOffers(MED, null, false)).thenReturn(List.of());
    service.getDetail(null, null, null, null, MED, false, "ip");

    assertThat(MedicineSearchService.paiseToRupees(100)).isEqualByComparingTo("1.00");
    assertThat(MedicineSearchService.roundScore(0.987)).isEqualTo(0.99);
  }

  @Test
  void rateLimitExceeded() {
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    MedicineSearchService tight =
        new MedicineSearchService(
            searchStore,
            medicineStore,
            categoryStore,
            pharmacies,
            cache,
            limiter,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ObjectMapper());
    when(searchStore.search(anyString(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(new SearchPage(List.of(), 0));
    when(searchStore.bestOffers(anyList(), any(), any(), anyBoolean())).thenReturn(List.of());
    when(searchStore.didYouMean(anyString())).thenReturn(Optional.empty());
    for (int i = 0; i < 120; i++) {
      tight.search(
          null, "ab", null, null, null, null, null, null, null, null, false, false, false, 1, 20,
          "same");
    }
    assertThatThrownBy(
            () ->
                tight.search(
                    null, "ab", null, null, null, null, null, null, null, null, false, false, false,
                    1, 20, "same"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  private static MedicineRow row(boolean banned, String schedule, List<UUID> subs) {
    return new MedicineRow(
        MED,
        "Augmentin",
        "Amox",
        "GSK",
        CAT,
        "Antibiotics",
        "TABLET",
        new BigDecimal("10"),
        "TABLET",
        schedule,
        "30041090",
        12,
        21850L,
        null,
        true,
        banned,
        banned ? "ban" : null,
        0,
        0,
        subs,
        "desc",
        null,
        NOW,
        NOW);
  }
}
