package com.nammamedmate.teleconsult.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore.ListFilter;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore.Page;
import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
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
import org.springframework.dao.DuplicateKeyException;

class TeleconsultDoctorServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ADMIN = UUID.fromString("a1000001-0000-4000-8000-0000000000a1");
  private static final MedmatePrincipal SUPER =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private static final MedmatePrincipal OPS =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private static final MedmatePrincipal COMPLIANCE =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");

  private FakeStore store;
  private ConsultStore consultStore;
  private AesGcmCipher cipher;
  private TeleconsultDoctorService service;

  @BeforeEach
  void setUp() {
    store = new FakeStore();
    consultStore = mock(ConsultStore.class);
    when(consultStore.doctorPeriodStats(any(), any(), any()))
        .thenReturn(new ConsultStore.DoctorPeriodStats(0, null, 0, 0, null, List.of()));
    cipher = AesGcmCipher.fromBase64Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    service =
        new TeleconsultDoctorService(
            store,
            consultStore,
            cipher,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac001_createStartsUnavailableWithZeroTotals() {
    Map<String, Object> created = createDefault(SUPER, "KA11111");
    assertThat(created.get("is_available")).isEqualTo(false);
    assertThat(created.get("total_consults")).isEqualTo(0);
    assertThat(created.get("avg_rating")).isNull();
    assertThat(created).doesNotContainKey("internal_phone");
    TeleconsultDoctor row = store.byId.values().iterator().next();
    assertThat(row.internalPhoneCiphertext()).isNotEqualTo("+91-9123456780");
    assertThat(cipher.decrypt(row.internalPhoneCiphertext())).isEqualTo("+91-9123456780");
  }

  @Test
  void ac002_duplicateRegistrationReturns409() {
    createDefault(SUPER, "DL98765");
    assertThatThrownBy(() -> createDefault(SUPER, "DL98765"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REGISTRATION_NO_DUPLICATE");
  }

  @Test
  void ac003_availabilityRequiresAvatarAndBio() {
    Map<String, Object> created = createDefault(SUPER, "MH10001");
    UUID id = (UUID) created.get("id");
    TeleconsultDoctor incomplete = withProfile(store.byId.get(id), " ", " ");
    store.byId.put(id, incomplete);
    assertThatThrownBy(() -> service.setAvailability(SUPER, id, true))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCTOR_PROFILE_INCOMPLETE");
  }

  @Test
  void ac004_opsCannotCreate() {
    assertThatThrownBy(() -> createDefault(OPS, "TN20002"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void ac005_selectLeastRecentlyAssigned() {
    Instant older = Instant.parse("2026-07-20T00:00:00Z");
    Instant newer = Instant.parse("2026-07-23T00:00:00Z");
    TeleconsultDoctor a = doctor("KA1", older);
    TeleconsultDoctor b = doctor("KA2", newer);
    TeleconsultDoctor never = doctor("KA3", null);
    assertThat(
            TeleconsultDoctorService.selectLeastRecentlyAssigned(List.of(a, b))
                .orElseThrow()
                .registrationNo())
        .isEqualTo("KA1");
    assertThat(
            TeleconsultDoctorService.selectLeastRecentlyAssigned(List.of(a, never))
                .orElseThrow()
                .registrationNo())
        .isEqualTo("KA3");
    assertThat(TeleconsultDoctorService.selectLeastRecentlyAssigned(List.of())).isEmpty();
    assertThat(TeleconsultDoctorService.selectLeastRecentlyAssigned(null)).isEmpty();
  }

  @Test
  void ac006_runningAverageRating() {
    assertThat(TeleconsultDoctorService.runningAverageRating(new BigDecimal("4.60"), 50, 5))
        .isEqualByComparingTo("4.61");
    assertThat(TeleconsultDoctorService.runningAverageRating(null, 0, 5))
        .isEqualByComparingTo("5.00");
    assertThatThrownBy(() -> TeleconsultDoctorService.runningAverageRating(BigDecimal.ONE, -1, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TeleconsultDoctorService.runningAverageRating(BigDecimal.ONE, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ac007_statsTodayUsesDoctorConsultsToday() {
    Map<String, Object> created = createDefault(SUPER, "GJ30003");
    UUID id = (UUID) created.get("id");
    TeleconsultDoctor row = store.byId.get(id);
    store.byId.put(
        id,
        new TeleconsultDoctor(
            row.id(),
            row.name(),
            row.qualification(),
            row.registrationNo(),
            row.specialty(),
            row.languagesSpoken(),
            row.yearsExperience(),
            row.avatarUrl(),
            row.bio(),
            row.internalPhoneCiphertext(),
            row.available(),
            new BigDecimal("4.70"),
            row.totalConsults(),
            8,
            row.lastAssignedAt(),
            row.createdAt(),
            row.updatedAt(),
            row.deletedAt()));
    Map<String, Object> stats = service.stats(SUPER, id, "today");
    assertThat(((Number) stats.get("consults_today")).intValue()).isEqualTo(8);
    assertThat(((Number) stats.get("consults_period")).longValue()).isEqualTo(0L);
    assertThat((BigDecimal) stats.get("avg_rating")).isEqualByComparingTo(new BigDecimal("4.70"));
    assertThat(stats.get("consults_by_day")).isEqualTo(List.of());

    when(consultStore.doctorPeriodStats(any(), any(), any()))
        .thenReturn(
            new ConsultStore.DoctorPeriodStats(
                3L,
                new BigDecimal("6.5"),
                2L,
                1L,
                new BigDecimal("4.20"),
                List.of(Map.of("date", "2026-07-24", "count", 3L))));
    assertThat(service.stats(SUPER, id, "30d").get("avg_call_duration_minutes"))
        .isEqualTo(new BigDecimal("6.5"));
    assertThat(service.stats(SUPER, id, "90d").get("patient_satisfaction_rate"))
        .isEqualTo(new BigDecimal("4.20"));
    assertThat(service.stats(SUPER, id, "7d").get("consults_period")).isEqualTo(3L);
  }

  @Test
  void ac008_updateLanguagesReflectedImmediately() {
    Map<String, Object> created = createDefault(SUPER, "RJ40004");
    UUID id = (UUID) created.get("id");
    Map<String, Object> updated =
        service.update(
            SUPER,
            id,
            Map.of("languages_spoken", List.of("Hindi", "English", "Kannada", "Marathi")));
    assertThat(updated.get("languages"))
        .isEqualTo(List.of("Hindi", "English", "Kannada", "Marathi"));
    assertThat(store.byId.get(id).languagesSpoken()).contains("Marathi");
  }

  @Test
  void invalidQualificationRejected() {
    assertThatThrownBy(
            () ->
                service.create(
                    SUPER,
                    "Dr X",
                    "PhD",
                    "KA99999",
                    "General Medicine",
                    List.of("English"),
                    5,
                    "https://cdn.nammamedmate.com/x.jpg",
                    "bio",
                    "+91-9000000000"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_QUALIFICATION");
  }

  @Test
  void bumsAllowedAndListFilters() {
    createDefault(SUPER, "UP50005");
    Map<String, Object> bums =
        service.create(
            SUPER,
            "Dr Bums",
            "bums",
            "UP50006",
            "General Medicine",
            List.of("Hindi"),
            3,
            "https://cdn.nammamedmate.com/b.jpg",
            "bio text",
            "+91-9111111111");
    assertThat(bums.get("qualification")).isEqualTo("BUMS");
    assertThat(service.list(COMPLIANCE, false, "General Medicine", 1, 20).data()).isNotEmpty();
    assertThat(service.list(COMPLIANCE, null, null, null, null).meta().page()).isEqualTo(1);
  }

  @Test
  void setAvailabilityAndStatsRoles() {
    Map<String, Object> created = createDefault(SUPER, "WB60006");
    UUID id = (UUID) created.get("id");
    Map<String, Object> avail = service.setAvailability(SUPER, id, true);
    assertThat(avail.get("is_available")).isEqualTo(true);
    assertThat(service.stats(OPS, id, null).get("period")).isEqualTo("7d");
    assertThatThrownBy(() -> service.stats(COMPLIANCE, id, "7d"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.stats(SUPER, id, "year"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.setAvailability(SUPER, id, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updatePatchBranchesAndDuplicateOnInsertRace() {
    Map<String, Object> created = createDefault(SUPER, "HR70007");
    UUID id = (UUID) created.get("id");
    createDefault(SUPER, "HR70008");
    Map<String, Object> patch = new LinkedHashMap<>();
    patch.put("name", "Dr Updated");
    patch.put("qualification", "MBBS MD");
    patch.put("registration_no", "HR70008");
    assertThatThrownBy(() -> service.update(SUPER, id, patch))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REGISTRATION_NO_DUPLICATE");

    Map<String, Object> ok = new LinkedHashMap<>();
    ok.put("name", "Dr Updated");
    ok.put("qualification", "MBBS MD");
    ok.put("registration_no", "hr70007");
    ok.put("specialty", "Pediatrics");
    ok.put("years_experience", 9);
    ok.put("avatar_url", "https://cdn.nammamedmate.com/u.jpg");
    ok.put("bio", "new bio");
    ok.put("internal_phone", "+91-9222222222");
    ok.put("languages_spoken", List.of("English"));
    Map<String, Object> updated = service.update(SUPER, id, ok);
    assertThat(updated.get("name")).isEqualTo("Dr Updated");
    assertThat(updated).doesNotContainKey("internal_phone");
    assertThat(service.update(SUPER, id, null).get("id")).isEqualTo(id);
    assertThat(service.update(SUPER, id, Map.of()).get("specialty")).isEqualTo("Pediatrics");
  }

  @Test
  void duplicateKeyOnInsertAndUpdateMapped() {
    store.failInsert = true;
    assertThatThrownBy(() -> createDefault(SUPER, "OR80008"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REGISTRATION_NO_DUPLICATE");
    store.failInsert = false;
    Map<String, Object> created = createDefault(SUPER, "OR80009");
    store.failUpdate = true;
    assertThatThrownBy(() -> service.update(SUPER, (UUID) created.get("id"), Map.of("name", "X")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REGISTRATION_NO_DUPLICATE");
  }

  @Test
  void validationBranches() {
    assertThatThrownBy(() -> TeleconsultDoctorService.requireQualification(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_QUALIFICATION");
    assertThatThrownBy(() -> TeleconsultDoctorService.requireRegistrationNo(" "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> TeleconsultDoctorService.requireRegistrationNo("12345"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    SUPER,
                    "Dr",
                    "MBBS",
                    "KL90001",
                    "x".repeat(101),
                    List.of("English"),
                    1,
                    "https://a",
                    "bio",
                    "+91"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    SUPER,
                    "Dr",
                    "MBBS",
                    "KL90002",
                    "GP",
                    List.of("English"),
                    1,
                    "https://a",
                    "b".repeat(501),
                    "+91"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    SUPER, "Dr", "MBBS", "KL90003", "GP", List.of(), 1, "https://a", "bio", "+91"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    SUPER,
                    "Dr",
                    "MBBS",
                    "KL90004",
                    "GP",
                    List.of("  "),
                    1,
                    "https://a",
                    "bio",
                    "+91"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    SUPER,
                    "Dr",
                    "MBBS",
                    "KL90005",
                    "GP",
                    List.of("En"),
                    0,
                    "https://a",
                    "bio",
                    "+91"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.update(SUPER, Ids.newId(), Map.of("name", "x")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCTOR_NOT_FOUND");
    assertThatThrownBy(() -> service.list(null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.update(
                    SUPER,
                    (UUID) createDefault(SUPER, "KL90006").get("id"),
                    Map.of("languages_spoken", "English")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.update(
                    SUPER,
                    (UUID) createDefault(SUPER, "KL90007").get("id"),
                    Map.of("years_experience", "nope")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    UUID id = (UUID) createDefault(SUPER, "KL90008").get("id");
    assertThat(service.update(SUPER, id, Map.of("years_experience", 4L)).get("years_experience"))
        .isEqualTo(4);
    assertThat(service.resetConsultsToday()).isEqualTo(0);
  }

  @Test
  void rateLimited() {
    RateLimiter limiter = mock(RateLimiter.class);
    when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    when(limiter.secondsUntilAvailable(anyString(), anyInt(), anyInt())).thenReturn(5);
    TeleconsultDoctorService limited =
        new TeleconsultDoctorService(
            store, consultStore, cipher, limiter, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> limited.list(SUPER, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  private Map<String, Object> createDefault(MedmatePrincipal principal, String reg) {
    return service.create(
        principal,
        "Dr. Kavitha Reddy",
        "MBBS MS",
        reg,
        "General Medicine",
        List.of("Telugu", "English", "Hindi"),
        8,
        "https://cdn.nammamedmate.com/doctors/kavitha-reddy.jpg",
        "Dr. Kavitha Reddy is a General Medicine specialist.",
        "+91-9123456780");
  }

  private static TeleconsultDoctor doctor(String reg, Instant lastAssigned) {
    return new TeleconsultDoctor(
        Ids.newId(),
        "Dr " + reg,
        "MBBS",
        reg,
        "General Medicine",
        List.of("English"),
        5,
        "https://cdn.nammamedmate.com/x.jpg",
        "bio",
        "cipher",
        true,
        null,
        0,
        0,
        lastAssigned,
        NOW,
        NOW,
        null);
  }

  private static TeleconsultDoctor withProfile(TeleconsultDoctor d, String avatar, String bio) {
    return new TeleconsultDoctor(
        d.id(),
        d.name(),
        d.qualification(),
        d.registrationNo(),
        d.specialty(),
        d.languagesSpoken(),
        d.yearsExperience(),
        avatar,
        bio,
        d.internalPhoneCiphertext(),
        d.available(),
        d.avgRating(),
        d.totalConsults(),
        d.consultsToday(),
        d.lastAssignedAt(),
        d.createdAt(),
        d.updatedAt(),
        d.deletedAt());
  }

  private static final class FakeStore implements TeleconsultDoctorStore {
    final Map<UUID, TeleconsultDoctor> byId = new ConcurrentHashMap<>();
    boolean failInsert;
    boolean failUpdate;

    @Override
    public void insert(TeleconsultDoctor doctor) {
      if (failInsert) {
        throw new DuplicateKeyException("dup");
      }
      byId.put(doctor.id(), doctor);
    }

    @Override
    public void update(TeleconsultDoctor doctor) {
      if (failUpdate) {
        throw new DuplicateKeyException("dup");
      }
      byId.put(doctor.id(), doctor);
    }

    @Override
    public Optional<TeleconsultDoctor> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<TeleconsultDoctor> findByRegistrationNo(String registrationNo) {
      return byId.values().stream()
          .filter(d -> d.registrationNo().equalsIgnoreCase(registrationNo))
          .findFirst();
    }

    @Override
    public Page list(ListFilter filter) {
      List<TeleconsultDoctor> items = new ArrayList<>(byId.values());
      if (filter.available() != null) {
        items = items.stream().filter(d -> d.available() == filter.available()).toList();
      }
      if (filter.specialty() != null && !filter.specialty().isBlank()) {
        String s = filter.specialty().trim();
        items = items.stream().filter(d -> d.specialty().equalsIgnoreCase(s)).toList();
      }
      return new Page(items, items.size());
    }

    @Override
    public int resetConsultsToday() {
      int n = 0;
      for (TeleconsultDoctor d : byId.values()) {
        if (d.consultsToday() != 0) {
          n++;
        }
      }
      return n;
    }

    @Override
    public List<TeleconsultDoctor> listAvailable() {
      return byId.values().stream().filter(TeleconsultDoctor::available).toList();
    }
  }
}
