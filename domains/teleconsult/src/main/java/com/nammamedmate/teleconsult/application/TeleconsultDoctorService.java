package com.nammamedmate.teleconsult.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.DoctorPeriodStats;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore.ListFilter;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore.Page;
import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeleconsultDoctorService {

  public static final Set<String> ALLOWED_QUALIFICATIONS =
      Set.of("MBBS", "MBBS MD", "MBBS MS", "BDS", "BAMS", "BHMS", "BUMS");

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final Set<String> READ_ROLES =
      Set.of(
          AuthRole.ADMIN_SUPER.name(),
          AuthRole.ADMIN_COMPLIANCE.name(),
          AuthRole.ADMIN_OPERATIONS.name());
  private static final Set<String> STATS_ROLES =
      Set.of(AuthRole.ADMIN_SUPER.name(), AuthRole.ADMIN_OPERATIONS.name());
  private static final Set<String> PERIODS = Set.of("today", "7d", "30d", "90d");
  private static final Pattern REGISTRATION_NO = Pattern.compile("^[A-Za-z]{2}[0-9]+$");

  private final TeleconsultDoctorStore store;
  private final ConsultStore consultStore;
  private final AesGcmCipher phoneCipher;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public TeleconsultDoctorService(
      TeleconsultDoctorStore store,
      ConsultStore consultStore,
      @Qualifier("teleconsultPhoneCipher") AesGcmCipher phoneCipher,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.consultStore = consultStore;
    this.phoneCipher = phoneCipher;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(List<Map<String, Object>> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? List.of() : List.copyOf(data);
    }
  }

  public ListResult list(
      MedmatePrincipal principal,
      Boolean isAvailable,
      String specialty,
      Integer page,
      Integer limit) {
    requireRead(principal);
    rateLimit("teleconsult:list:" + principal.subject(), 30, 60);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    Page result = store.list(new ListFilter(isAvailable, specialty, p, lim));
    return new ListResult(
        result.items().stream().map(this::toListItem).toList(),
        PaginationMeta.of(p, lim, result.total()));
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal,
      String name,
      String qualification,
      String registrationNo,
      String specialty,
      List<String> languagesSpoken,
      Integer yearsExperience,
      String avatarUrl,
      String bio,
      String internalPhone) {
    requireSuper(principal);
    rateLimit("teleconsult:create:" + principal.subject(), 10, 60);
    Instant now = clock.instant();
    String qual = requireQualification(qualification);
    String reg = requireRegistrationNo(registrationNo);
    if (store.findByRegistrationNo(reg).isPresent()) {
      throw new AppException(
          "REGISTRATION_NO_DUPLICATE", "Doctor with this registration_no already exists", 409);
    }
    String nm = requireNonBlank(name, "name");
    String spec = requireSpecialty(specialty);
    List<String> langs = requireLanguages(languagesSpoken);
    int years = requireYears(yearsExperience);
    String avatar = requireNonBlank(avatarUrl, "avatar_url");
    String bioText = requireBio(bio);
    String phonePlain = requireNonBlank(internalPhone, "internal_phone");

    TeleconsultDoctor doctor =
        new TeleconsultDoctor(
            Ids.newId(),
            nm,
            qual,
            reg,
            spec,
            langs,
            years,
            avatar,
            bioText,
            phoneCipher.encrypt(phonePlain),
            false,
            null,
            0,
            0,
            null,
            now,
            now,
            null);
    try {
      store.insert(doctor);
    } catch (DuplicateKeyException ex) {
      throw new AppException(
          "REGISTRATION_NO_DUPLICATE", "Doctor with this registration_no already exists", 409);
    }
    return toCreateItem(doctor);
  }

  @Transactional
  public Map<String, Object> update(
      MedmatePrincipal principal, UUID id, Map<String, Object> patch) {
    requireSuper(principal);
    rateLimit("teleconsult:update:" + principal.subject(), 10, 60);
    TeleconsultDoctor existing = requireDoctor(id);
    Instant now = clock.instant();

    String name = existing.name();
    String qualification = existing.qualification();
    String registrationNo = existing.registrationNo();
    String specialty = existing.specialty();
    List<String> languages = existing.languagesSpoken();
    int years = existing.yearsExperience();
    String avatar = existing.avatarUrl();
    String bio = existing.bio();
    String phoneCiphertext = existing.internalPhoneCiphertext();

    if (patch != null) {
      if (patch.containsKey("name")) {
        name = requireNonBlank(asString(patch.get("name")), "name");
      }
      if (patch.containsKey("qualification")) {
        qualification = requireQualification(asString(patch.get("qualification")));
      }
      if (patch.containsKey("registration_no")) {
        String nextReg = requireRegistrationNo(asString(patch.get("registration_no")));
        if (!nextReg.equals(existing.registrationNo())) {
          if (store.findByRegistrationNo(nextReg).isPresent()) {
            throw new AppException(
                "REGISTRATION_NO_DUPLICATE",
                "Doctor with this registration_no already exists",
                409);
          }
          registrationNo = nextReg;
        }
      }
      if (patch.containsKey("specialty")) {
        specialty = requireSpecialty(asString(patch.get("specialty")));
      }
      if (patch.containsKey("languages_spoken")) {
        languages = requireLanguages(asStringList(patch.get("languages_spoken")));
      }
      if (patch.containsKey("years_experience")) {
        years = requireYears(asInteger(patch.get("years_experience")));
      }
      if (patch.containsKey("avatar_url")) {
        avatar = requireNonBlank(asString(patch.get("avatar_url")), "avatar_url");
      }
      if (patch.containsKey("bio")) {
        bio = requireBio(asString(patch.get("bio")));
      }
      if (patch.containsKey("internal_phone")) {
        phoneCiphertext =
            phoneCipher.encrypt(
                requireNonBlank(asString(patch.get("internal_phone")), "internal_phone"));
      }
    }

    TeleconsultDoctor updated =
        new TeleconsultDoctor(
            existing.id(),
            name,
            qualification,
            registrationNo,
            specialty,
            languages,
            years,
            avatar,
            bio,
            phoneCiphertext,
            existing.available(),
            existing.avgRating(),
            existing.totalConsults(),
            existing.consultsToday(),
            existing.lastAssignedAt(),
            existing.createdAt(),
            now,
            existing.deletedAt());
    try {
      store.update(updated);
    } catch (DuplicateKeyException ex) {
      throw new AppException(
          "REGISTRATION_NO_DUPLICATE", "Doctor with this registration_no already exists", 409);
    }
    return toListItem(updated);
  }

  @Transactional
  public Map<String, Object> setAvailability(
      MedmatePrincipal principal, UUID id, Boolean isAvailable) {
    requireSuper(principal);
    rateLimit("teleconsult:availability:" + principal.subject(), 30, 60);
    if (isAvailable == null) {
      throw new AppException("VALIDATION_ERROR", "is_available is required", 400);
    }
    TeleconsultDoctor existing = requireDoctor(id);
    if (isAvailable && !existing.profileComplete()) {
      throw new AppException(
          "DOCTOR_PROFILE_INCOMPLETE", "Cannot set available=true without avatar_url and bio", 422);
    }
    Instant now = clock.instant();
    TeleconsultDoctor updated =
        new TeleconsultDoctor(
            existing.id(),
            existing.name(),
            existing.qualification(),
            existing.registrationNo(),
            existing.specialty(),
            existing.languagesSpoken(),
            existing.yearsExperience(),
            existing.avatarUrl(),
            existing.bio(),
            existing.internalPhoneCiphertext(),
            isAvailable,
            existing.avgRating(),
            existing.totalConsults(),
            existing.consultsToday(),
            existing.lastAssignedAt(),
            existing.createdAt(),
            now,
            existing.deletedAt());
    store.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id());
    data.put("is_available", updated.available());
    data.put("updated_at", updated.updatedAt());
    return data;
  }

  public Map<String, Object> stats(MedmatePrincipal principal, UUID id, String periodRaw) {
    requireStats(principal);
    rateLimit("teleconsult:stats:" + principal.subject(), 30, 60);
    TeleconsultDoctor doctor = requireDoctor(id);
    String period =
        periodRaw == null || periodRaw.isBlank() ? "7d" : periodRaw.trim().toLowerCase(Locale.ROOT);
    if (!PERIODS.contains(period)) {
      throw new AppException("VALIDATION_ERROR", "period must be today, 7d, 30d, or 90d", 400);
    }
    Instant[] range = periodRange(period);
    DoctorPeriodStats periodStats = consultStore.doctorPeriodStats(doctor.id(), range[0], range[1]);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("doctor_id", doctor.id());
    data.put("period", period);
    data.put("consults_today", doctor.consultsToday());
    data.put("consults_period", periodStats.consultsPeriod());
    data.put(
        "avg_call_duration_minutes",
        periodStats.avgCallDurationMinutes() == null ? 0 : periodStats.avgCallDurationMinutes());
    data.put("avg_rating", doctor.avgRating());
    data.put("e_prescriptions_issued", periodStats.ePrescriptionsIssued());
    data.put("advice_only_consults", periodStats.adviceOnlyConsults());
    data.put(
        "patient_satisfaction_rate",
        periodStats.patientSatisfactionRate() == null ? 0 : periodStats.patientSatisfactionRate());
    data.put("consults_by_day", periodStats.consultsByDay());
    return data;
  }

  private Instant[] periodRange(String period) {
    Instant end = clock.instant();
    LocalDate todayIst = LocalDate.now(clock.withZone(IST));
    Instant start = todayIst.minusDays(6).atStartOfDay(IST).toInstant();
    if ("today".equals(period)) {
      start = todayIst.atStartOfDay(IST).toInstant();
    } else if ("30d".equals(period)) {
      start = todayIst.minusDays(29).atStartOfDay(IST).toInstant();
    } else if ("90d".equals(period)) {
      start = todayIst.minusDays(89).atStartOfDay(IST).toInstant();
    }
    return new Instant[] {start, end};
  }

  /** Midnight IST: zero consults_today counters. */
  @Transactional
  public int resetConsultsToday() {
    return store.resetConsultsToday();
  }

  /**
   * Load-balancing: least-recently-assigned among candidates (null last_assigned_at wins). For
   * STORY-002 assign path reuse.
   */
  public static Optional<TeleconsultDoctor> selectLeastRecentlyAssigned(
      List<TeleconsultDoctor> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    return candidates.stream()
        .min(
            Comparator.comparing(
                    TeleconsultDoctor::lastAssignedAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(TeleconsultDoctor::id));
  }

  /** Running average after a new consult rating (AC-006). */
  public static BigDecimal runningAverageRating(BigDecimal oldAvg, int previousCount, int rating) {
    if (previousCount < 0) {
      throw new IllegalArgumentException("previousCount must be >= 0");
    }
    if (rating < 1 || rating > 5) {
      throw new IllegalArgumentException("rating must be 1..5");
    }
    BigDecimal old = oldAvg == null ? BigDecimal.ZERO : oldAvg;
    BigDecimal numerator =
        old.multiply(BigDecimal.valueOf(previousCount)).add(BigDecimal.valueOf(rating));
    return numerator.divide(BigDecimal.valueOf(previousCount + 1L), 2, RoundingMode.HALF_UP);
  }

  private Map<String, Object> toListItem(TeleconsultDoctor d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", d.id());
    m.put("name", d.name());
    m.put("qualification", d.qualification());
    m.put("registration_no", d.registrationNo());
    m.put("specialty", d.specialty());
    m.put("rating", d.avgRating());
    m.put("years_experience", d.yearsExperience());
    m.put("languages", d.languagesSpoken());
    m.put("is_available", d.available());
    m.put("consults_today", d.consultsToday());
    m.put("total_consults", d.totalConsults());
    m.put("last_assigned_at", d.lastAssignedAt());
    m.put("avatar_url", d.avatarUrl());
    m.put("created_at", d.createdAt());
    return m;
  }

  private Map<String, Object> toCreateItem(TeleconsultDoctor d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", d.id());
    m.put("name", d.name());
    m.put("qualification", d.qualification());
    m.put("registration_no", d.registrationNo());
    m.put("specialty", d.specialty());
    m.put("languages_spoken", d.languagesSpoken());
    m.put("years_experience", d.yearsExperience());
    m.put("avatar_url", d.avatarUrl());
    m.put("is_available", d.available());
    m.put("avg_rating", d.avgRating());
    m.put("total_consults", d.totalConsults());
    m.put("created_at", d.createdAt());
    return m;
  }

  private TeleconsultDoctor requireDoctor(UUID id) {
    return store
        .findById(id)
        .orElseThrow(() -> new AppException("DOCTOR_NOT_FOUND", "Doctor ID not found", 404));
  }

  private void requireRead(MedmatePrincipal principal) {
    if (principal == null || !READ_ROLES.contains(principal.role().name())) {
      throw new AppException("FORBIDDEN", "Forbidden", 403);
    }
  }

  private void requireStats(MedmatePrincipal principal) {
    if (principal == null || !STATS_ROLES.contains(principal.role().name())) {
      throw new AppException("FORBIDDEN", "Forbidden", 403);
    }
  }

  private void requireSuper(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Forbidden", 403);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  static String requireQualification(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("INVALID_QUALIFICATION", "Qualification not in allowed ENUM", 422);
    }
    String q = raw.trim().replaceAll("\\s+", " ");
    for (String allowed : ALLOWED_QUALIFICATIONS) {
      if (allowed.equalsIgnoreCase(q)) {
        return allowed;
      }
    }
    throw new AppException("INVALID_QUALIFICATION", "Qualification not in allowed ENUM", 422);
  }

  static String requireRegistrationNo(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "registration_no is required", 400);
    }
    String reg = raw.trim().toUpperCase(Locale.ROOT);
    if (!REGISTRATION_NO.matcher(reg).matches()) {
      throw new AppException(
          "VALIDATION_ERROR", "registration_no must be state code + numeric", 400);
    }
    return reg;
  }

  private static String requireSpecialty(String raw) {
    String s = requireNonBlank(raw, "specialty");
    if (s.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "specialty must be at most 100 characters", 400);
    }
    return s;
  }

  private static String requireBio(String raw) {
    String s = requireNonBlank(raw, "bio");
    if (s.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "bio must be at most 500 characters", 400);
    }
    return s;
  }

  private static List<String> requireLanguages(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      throw new AppException(
          "VALIDATION_ERROR", "languages_spoken requires at least 1 language", 400);
    }
    List<String> cleaned =
        raw.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
    if (cleaned.isEmpty()) {
      throw new AppException(
          "VALIDATION_ERROR", "languages_spoken requires at least 1 language", 400);
    }
    return cleaned;
  }

  private static int requireYears(Integer years) {
    if (years == null || years <= 0) {
      throw new AppException("VALIDATION_ERROR", "years_experience must be > 0", 400);
    }
    return years;
  }

  private static String requireNonBlank(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    return raw.trim();
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private static List<String> asStringList(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(v -> v == null ? null : String.valueOf(v)).toList();
    }
    throw new AppException("VALIDATION_ERROR", "languages_spoken must be an array", 400);
  }

  private static Integer asInteger(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException ex) {
      throw new AppException("VALIDATION_ERROR", "years_experience must be an integer", 400);
    }
  }
}
