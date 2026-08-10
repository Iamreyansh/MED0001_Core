package com.nammamedmate.teleconsult.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore.ListFilter;
import com.nammamedmate.teleconsult.application.port.out.TeleconsultDoctorStore.Page;
import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcTeleconsultDoctorStoreTest {

  private JdbcTemplate jdbc;
  private JdbcTeleconsultDoctorStore store;
  private final ObjectMapper om = new ObjectMapper();
  private final Instant now = Instant.parse("2026-07-24T10:00:00Z");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    store = new JdbcTeleconsultDoctorStore(jdbc, om);
  }

  @Test
  void insertUpdateAndResetDelegate() {
    TeleconsultDoctor d = sample(Ids.newId());
    when(jdbc.update(anyString(), ArgumentMatchers.<Object>any())).thenReturn(1);
    when(jdbc.update(anyString())).thenReturn(2);
    store.insert(d);
    store.update(d);
    assertThat(store.resetConsultsToday()).isEqualTo(2);
    verify(jdbc).update(anyString());
  }

  @Test
  void findByIdAndRegistration() throws Exception {
    UUID id = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(mapFrom(inv.getArgument(1), sample(id))));
    assertThat(store.findById(id)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq("KA11111")))
        .thenAnswer(inv -> List.of(mapFrom(inv.getArgument(1), sample(id))));
    assertThat(store.findByRegistrationNo("KA11111")).isPresent();
    assertThat(store.findByRegistrationNo(null)).isEmpty();
    assertThat(store.findByRegistrationNo("  ")).isEmpty();
  }

  @Test
  void listWithFilters() throws Exception {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(mapFrom(inv.getArgument(1), sample(Ids.newId()))));
    Page page = store.list(new ListFilter(true, "General Medicine", 1, 20));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    Page empty = store.list(new ListFilter(null, "  ", 0, 0));
    assertThat(empty.total()).isEqualTo(0);
  }

  @Test
  void mapRowParsesNullsAndInvalidJson() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(Ids.newId());
    when(rs.getString("name")).thenReturn("Dr");
    when(rs.getString("qualification")).thenReturn("MBBS");
    when(rs.getString("registration_no")).thenReturn("KA1");
    when(rs.getString("specialty")).thenReturn("GP");
    when(rs.getString("languages_spoken")).thenReturn(null);
    when(rs.getInt("years_experience")).thenReturn(1);
    when(rs.getString("avatar_url")).thenReturn("a");
    when(rs.getString("bio")).thenReturn("b");
    when(rs.getString("internal_phone")).thenReturn("c");
    when(rs.getBoolean("is_available")).thenReturn(false);
    when(rs.getBigDecimal("avg_rating")).thenReturn(null);
    when(rs.getInt("total_consults")).thenReturn(0);
    when(rs.getInt("consults_today")).thenReturn(0);
    when(rs.getTimestamp("last_assigned_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    Optional<TeleconsultDoctor> row = store.findById(Ids.newId());
    assertThat(row).isPresent();
    assertThat(row.get().languagesSpoken()).isEmpty();

    when(rs.getString("languages_spoken")).thenReturn("{not-json");
    assertThatThrownBy(
            () -> {
              when(jdbc.query(anyString(), any(RowMapper.class), any()))
                  .thenAnswer(
                      inv -> {
                        RowMapper<?> mapper = inv.getArgument(1);
                        return List.of(mapper.mapRow(rs, 0));
                      });
              store.findById(Ids.newId());
            })
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void listAvailableDelegates() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(mapFrom(inv.getArgument(1), sample(Ids.newId()))));
    assertThat(store.listAvailable()).hasSize(1);
  }

  @Test
  void toJsonFailure() {
    ObjectMapper failing = mock(ObjectMapper.class);
    try {
      when(failing.writeValueAsString(any()))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError(e);
    }
    JdbcTeleconsultDoctorStore bad = new JdbcTeleconsultDoctorStore(jdbc, failing);
    assertThatThrownBy(() -> bad.insert(sample(Ids.newId())))
        .isInstanceOf(IllegalStateException.class);
  }

  private TeleconsultDoctor sample(UUID id) {
    return new TeleconsultDoctor(
        id,
        "Dr Kavitha",
        "MBBS MS",
        "KA11111",
        "General Medicine",
        List.of("English", "Hindi"),
        8,
        "https://cdn.nammamedmate.com/x.jpg",
        "bio",
        "cipher",
        false,
        new BigDecimal("4.50"),
        0,
        0,
        now,
        now,
        now,
        null);
  }

  private TeleconsultDoctor mapFrom(RowMapper<TeleconsultDoctor> mapper, TeleconsultDoctor d)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(d.id());
    when(rs.getString("name")).thenReturn(d.name());
    when(rs.getString("qualification")).thenReturn(d.qualification());
    when(rs.getString("registration_no")).thenReturn(d.registrationNo());
    when(rs.getString("specialty")).thenReturn(d.specialty());
    when(rs.getString("languages_spoken")).thenReturn(om.writeValueAsString(d.languagesSpoken()));
    when(rs.getInt("years_experience")).thenReturn(d.yearsExperience());
    when(rs.getString("avatar_url")).thenReturn(d.avatarUrl());
    when(rs.getString("bio")).thenReturn(d.bio());
    when(rs.getString("internal_phone")).thenReturn(d.internalPhoneCiphertext());
    when(rs.getBoolean("is_available")).thenReturn(d.available());
    when(rs.getBigDecimal("avg_rating")).thenReturn(d.avgRating());
    when(rs.getInt("total_consults")).thenReturn(d.totalConsults());
    when(rs.getInt("consults_today")).thenReturn(d.consultsToday());
    when(rs.getTimestamp("last_assigned_at")).thenReturn(Timestamp.from(d.lastAssignedAt()));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(d.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(d.updatedAt()));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    return mapper.mapRow(rs, 0);
  }
}
