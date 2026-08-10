package com.nammamedmate.teleconsult.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.teleconsult.adapter.out.persistence.JdbcTeleconsultDoctorStore;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore.ListFilter;
import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class TeleconsultCoverageGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final MedmatePrincipal SUPER =
      new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private static final MedmatePrincipal CUSTOMER =
      new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @Test
  void remainingServiceBranches() {
    FakeStore store = new FakeStore();
    AesGcmCipher cipher =
        AesGcmCipher.fromBase64Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    ConsultStore consultStore = mock(ConsultStore.class);
    when(consultStore.doctorPeriodStats(any(), any(), any()))
        .thenReturn(new ConsultStore.DoctorPeriodStats(0, null, 0, 0, null, List.of()));
    TeleconsultDoctorService service =
        new TeleconsultDoctorService(
            store,
            consultStore,
            cipher,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    Map<String, Object> created =
        service.create(
            SUPER,
            "Dr Gap",
            "MBBS",
            "PB10001",
            "GP",
            java.util.Arrays.asList("English", null, "  Hindi  "),
            2,
            "https://cdn.nammamedmate.com/g.jpg",
            "bio",
            "+91-9333333333");
    UUID id = (UUID) created.get("id");

    assertThat(service.list(SUPER, null, null, 0, 500).meta().limit()).isEqualTo(100);
    assertThat(service.setAvailability(SUPER, id, false).get("is_available")).isEqualTo(false);
    assertThat(service.stats(SUPER, id, "  ").get("period")).isEqualTo("7d");

    assertThatThrownBy(() -> service.list(CUSTOMER, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.stats(null, id, "7d"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () -> service.create(null, "a", "MBBS", "PB1", "GP", List.of("E"), 1, "a", "b", "c"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> TeleconsultDoctorService.requireQualification("   "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_QUALIFICATION");
    assertThatThrownBy(() -> TeleconsultDoctorService.requireRegistrationNo(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    SUPER, "Dr", "MBBS", "PB10002", "GP", null, 1, "https://a", "bio", "+91"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    SUPER,
                    "Dr",
                    "MBBS",
                    "PB10003",
                    "GP",
                    List.of("E"),
                    null,
                    "https://a",
                    "bio",
                    "+91"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    SUPER,
                    null,
                    "MBBS",
                    "PB10004",
                    "GP",
                    List.of("E"),
                    1,
                    "https://a",
                    "bio",
                    "+91"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Map<String, Object> patch = new HashMap<>();
    patch.put("name", null);
    assertThatThrownBy(() -> service.update(SUPER, id, patch))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    patch.clear();
    patch.put("languages_spoken", null);
    assertThatThrownBy(() -> service.update(SUPER, id, patch))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    patch.clear();
    patch.put("years_experience", null);
    assertThatThrownBy(() -> service.update(SUPER, id, patch))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    patch.clear();
    patch.put("registration_no", "PB10001");
    assertThat(service.update(SUPER, id, patch).get("registration_no")).isEqualTo("PB10001");
    patch.clear();
    patch.put("registration_no", "PB19999");
    assertThat(service.update(SUPER, id, patch).get("registration_no")).isEqualTo("PB19999");
    patch.clear();
    patch.put("name", "  ");
    assertThatThrownBy(() -> service.update(SUPER, id, patch))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    patch.clear();
    patch.put("years_experience", "11");
    assertThat(service.update(SUPER, id, patch).get("years_experience")).isEqualTo(11);
    patch.clear();
    patch.put("languages_spoken", java.util.Arrays.asList("English", null, 3));
    assertThat(service.update(SUPER, id, patch).get("languages"))
        .isEqualTo(List.of("English", "3"));

    assertThat(service.list(SUPER, null, null, 1, 0).meta().limit()).isEqualTo(20);
    assertThat(new TeleconsultDoctorService.ListResult(null, null).data()).isEmpty();
    assertThat(
            new com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore.Page(
                    null, 0)
                .items())
        .isEmpty();

    assertThatThrownBy(() -> TeleconsultDoctorService.runningAverageRating(BigDecimal.ONE, 1, 9))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jdbcListAndParseLanguageBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper om = new ObjectMapper();
    JdbcTeleconsultDoctorStore store = new JdbcTeleconsultDoctorStore(jdbc, om);

    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
        .thenReturn(0L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.list(new ListFilter(null, null, 2, 5)).total()).isZero();

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(Ids.newId());
    when(rs.getString("name")).thenReturn("Dr");
    when(rs.getString("qualification")).thenReturn("MBBS");
    when(rs.getString("registration_no")).thenReturn("PB2");
    when(rs.getString("specialty")).thenReturn("GP");
    when(rs.getString("languages_spoken")).thenReturn("   ");
    when(rs.getInt("years_experience")).thenReturn(1);
    when(rs.getString("avatar_url")).thenReturn("a");
    when(rs.getString("bio")).thenReturn("b");
    when(rs.getString("internal_phone")).thenReturn("c");
    when(rs.getBoolean("is_available")).thenReturn(false);
    when(rs.getBigDecimal("avg_rating")).thenReturn(null);
    when(rs.getInt("total_consults")).thenReturn(0);
    when(rs.getInt("consults_today")).thenReturn(0);
    when(rs.getTimestamp("last_assigned_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              RowMapper<TeleconsultDoctor> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(Ids.newId()).orElseThrow().languagesSpoken()).isEmpty();

    when(rs.getString("languages_spoken")).thenReturn("null");
    assertThat(store.findById(Ids.newId()).orElseThrow().languagesSpoken()).isEmpty();
  }

  @Test
  void profileCompleteBranches() {
    Instant now = NOW;
    assertThat(
            new TeleconsultDoctor(
                    Ids.newId(),
                    "n",
                    "MBBS",
                    "X1",
                    "GP",
                    List.of(),
                    1,
                    null,
                    "bio",
                    "c",
                    false,
                    null,
                    0,
                    0,
                    null,
                    now,
                    now,
                    null)
                .profileComplete())
        .isFalse();
    assertThat(
            new TeleconsultDoctor(
                    Ids.newId(),
                    "n",
                    "MBBS",
                    "X2",
                    "GP",
                    List.of(),
                    1,
                    "url",
                    "  ",
                    "c",
                    false,
                    null,
                    0,
                    0,
                    null,
                    now,
                    now,
                    null)
                .profileComplete())
        .isFalse();
    assertThat(
            new TeleconsultDoctor(
                    Ids.newId(),
                    "n",
                    "MBBS",
                    "X3",
                    "GP",
                    List.of(),
                    1,
                    "url",
                    null,
                    "c",
                    false,
                    null,
                    0,
                    0,
                    null,
                    now,
                    now,
                    null)
                .profileComplete())
        .isFalse();
    assertThat(
            new TeleconsultDoctor(
                    Ids.newId(),
                    "n",
                    "MBBS",
                    "X4",
                    "GP",
                    List.of(),
                    1,
                    "",
                    "bio",
                    "c",
                    false,
                    null,
                    0,
                    0,
                    null,
                    now,
                    now,
                    null)
                .profileComplete())
        .isFalse();
  }

  private static final class FakeStore
      implements com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore {
    private final Map<UUID, TeleconsultDoctor> byId = new HashMap<>();

    @Override
    public void insert(TeleconsultDoctor doctor) {
      byId.put(doctor.id(), doctor);
    }

    @Override
    public void update(TeleconsultDoctor doctor) {
      byId.put(doctor.id(), doctor);
    }

    @Override
    public java.util.Optional<TeleconsultDoctor> findById(UUID id) {
      return java.util.Optional.ofNullable(byId.get(id));
    }

    @Override
    public java.util.Optional<TeleconsultDoctor> findByRegistrationNo(String registrationNo) {
      return byId.values().stream()
          .filter(d -> d.registrationNo().equalsIgnoreCase(registrationNo))
          .findFirst();
    }

    @Override
    public Page list(ListFilter filter) {
      return new Page(new ArrayList<>(byId.values()), byId.size());
    }

    @Override
    public int resetConsultsToday() {
      return 0;
    }

    @Override
    public List<TeleconsultDoctor> listAvailable() {
      return byId.values().stream().filter(TeleconsultDoctor::available).toList();
    }
  }
}
