package com.nammamedmate.teleconsult.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.ListFilter;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore.Page;
import com.nammamedmate.teleconsult.domain.Consult;
import com.nammamedmate.teleconsult.domain.Consult.MedicineNeed;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class JdbcConsultStoreTest {

  private JdbcTemplate jdbc;
  private JdbcConsultStore store;
  private final ObjectMapper om = new ObjectMapper();
  private final Instant now = Instant.parse("2026-07-24T10:00:00Z");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    store = new JdbcConsultStore(jdbc, om);
  }

  @Test
  void insertUpdateAndQueries() throws Exception {
    Consult c = sample(Ids.newId());
    when(jdbc.update(anyString(), ArgumentMatchers.<Object>any())).thenReturn(1);
    store.insert(c);
    store.update(c);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(c.id())))
        .thenAnswer(inv -> List.of(mapFrom(inv.getArgument(1), c)));
    assertThat(store.findById(c.id())).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(c.id()), eq(c.customerId())))
        .thenAnswer(inv -> List.of(mapFrom(inv.getArgument(1), c)));
    assertThat(store.findByIdForCustomer(c.id(), c.customerId())).isPresent();

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(c.customerId()))).thenReturn(2L);
    assertThat(store.countActiveByCustomer(c.customerId())).isEqualTo(2);
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(c.customerId()))).thenReturn(null);
    assertThat(store.countActiveByCustomer(c.customerId())).isEqualTo(0);

    assertThat(store.hasActiveCartModeConsult(null)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(c.cartId()))).thenReturn(true);
    assertThat(store.hasActiveCartModeConsult(c.cartId())).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(c.cartId()))).thenReturn(false);
    assertThat(store.hasActiveCartModeConsult(c.cartId())).isFalse();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getObject("e_prescription_id")).thenReturn(null);
              when(rs.getObject("cart_id")).thenReturn(c.cartId());
              when(rs.getBoolean("is_cart_mode")).thenReturn(true);
              when(rs.getObject("rating")).thenReturn(4);
              when(rs.getInt("rating")).thenReturn(4);
              when(rs.getString("doctor_name")).thenReturn("Dr X");
              return List.of(mapper.mapRow(rs, 0));
            });
    Page page = store.list(new ListFilter(c.customerId(), "REQUESTED", 1, 20));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).doctorName()).isEqualTo("Dr X");
    assertThat(page.items().get(0).rating()).isEqualTo(4);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    Page empty = store.list(new ListFilter(c.customerId(), "ALL", 0, 0));
    assertThat(empty.total()).isEqualTo(0);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Timestamp.class))).thenReturn(3L);
    assertThat(store.countQueuedNowAheadOrEqual(now)).isEqualTo(3);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Timestamp.class))).thenReturn(null);
    assertThat(store.countQueuedNowAheadOrEqual(now)).isEqualTo(0);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getObject("avg_min")).thenReturn(8);
              return ex.extractData(rs);
            });
    assertThat(store.rollingAvgCallDurationMinutes()).contains(8);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThat(store.rollingAvgCallDurationMinutes()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Timestamp.class)))
        .thenAnswer(inv -> List.of(mapFrom(inv.getArgument(1), c)));
    assertThat(store.findDueForAutoCancel(now)).hasSize(1);
    assertThat(store.findDueForScheduledAssign(now)).hasSize(1);

    when(jdbc.update(anyString(), ArgumentMatchers.<Object>any())).thenReturn(1);
    store.insertStatusEvent(
        new com.nammamedmate.teleconsult.domain.ConsultStatusEvent(
            Ids.newId(), c.id(), "CALLING", "IN_CALL", Ids.newId(), "n", now));

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getString("status")).thenReturn("IN_CALL");
              when(rs.getString("patient_name")).thenReturn("Ravi");
              when(rs.getString("patient_phone")).thenReturn("+91-9");
              when(rs.getString("doctor_name")).thenReturn("Dr");
              when(rs.getString("medicines_needing_rx"))
                  .thenReturn("[{\"name\":\"M\",\"reason\":\"REFILL\"}]");
              when(rs.getTimestamp("call_started_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listActiveQueue()).hasSize(1);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true, false);
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getLong("cnt")).thenReturn(2L);
              return ex.extractData(rs);
            });
    assertThat(store.countActiveByStatus().get("REQUESTED")).isEqualTo(2L);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getString("patient_name")).thenReturn("Ravi");
              when(rs.getString("doctor_name")).thenReturn("Dr");
              when(rs.getString("status")).thenReturn("COMPLETED");
              when(rs.getBigDecimal("duration_minutes"))
                  .thenReturn(new java.math.BigDecimal("7.00"));
              when(rs.getObject("e_prescription_id")).thenReturn(Ids.newId());
              when(rs.getBoolean("is_cart_mode")).thenReturn(true);
              when(rs.getObject("rating")).thenReturn(5);
              when(rs.getInt("rating")).thenReturn(5);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("call_ended_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(
            store
                .adminList(
                    new ConsultStore.AdminListFilter(
                        now.minusSeconds(10), now.plusSeconds(10), null, "COMPLETED", true, 1, 20))
                .total())
        .isEqualTo(1);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong("total_today")).thenReturn(2L);
              when(rs.getLong("completed")).thenReturn(1L);
              when(rs.getLong("in_progress")).thenReturn(1L);
              when(rs.getLong("cancelled")).thenReturn(0L);
              when(rs.getBigDecimal("avg_duration")).thenReturn(new java.math.BigDecimal("6.3"));
              when(rs.getBigDecimal("avg_rating")).thenReturn(new java.math.BigDecimal("4.6"));
              when(rs.getLong("pending_rating")).thenReturn(1L);
              return ex.extractData(rs);
            });
    assertThat(store.adminDayStats(now.minusSeconds(10), now.plusSeconds(10)).totalToday())
        .isEqualTo(2);

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(c.id()))).thenReturn(3L);
    assertThat(store.countRatingsByDoctor(c.id())).isEqualTo(3);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong("consults_period")).thenReturn(4L);
              when(rs.getBigDecimal("avg_duration")).thenReturn(new java.math.BigDecimal("5.0"));
              when(rs.getLong("e_rx")).thenReturn(2L);
              when(rs.getLong("advice_only")).thenReturn(1L);
              when(rs.getBigDecimal("satisfaction")).thenReturn(new java.math.BigDecimal("4.50"));
              return ex.extractData(rs);
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getDate("day")).thenReturn(java.sql.Date.valueOf("2026-07-24"));
              when(rs.getLong("cnt")).thenReturn(2L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(
            store
                .doctorPeriodStats(c.id(), now.minusSeconds(100), now.plusSeconds(100))
                .consultsPeriod())
        .isEqualTo(4);

    // adminList filters + null total + non-completed completed_at + blank status
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getString("patient_name")).thenReturn("Ravi");
              when(rs.getString("doctor_name")).thenReturn(null);
              when(rs.getString("status")).thenReturn("IN_CALL");
              when(rs.getBigDecimal("duration_minutes")).thenReturn(null);
              when(rs.getObject("e_prescription_id")).thenReturn(null);
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              when(rs.getObject("rating")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("call_ended_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(
            store
                .adminList(
                    new ConsultStore.AdminListFilter(
                        now.minusSeconds(10),
                        now.plusSeconds(10),
                        c.id(),
                        "COMPLETED",
                        false,
                        0,
                        0))
                .total())
        .isEqualTo(0);
    assertThat(
            store
                .adminList(
                    new ConsultStore.AdminListFilter(
                        now.minusSeconds(10), now.plusSeconds(10), null, null, null, 1, 20))
                .items()
                .get(0)
                .completedAt())
        .isNull();
    assertThat(
            store
                .adminList(
                    new ConsultStore.AdminListFilter(
                        now.minusSeconds(10), now.plusSeconds(10), null, "  ", null, 1, 20))
                .total())
        .isEqualTo(0);
    assertThat(
            store
                .adminList(
                    new ConsultStore.AdminListFilter(
                        now.minusSeconds(10), now.plusSeconds(10), null, "ALL", null, 1, 20))
                .total())
        .isEqualTo(0);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThat(store.adminDayStats(now, now.plusSeconds(1)).totalToday()).isEqualTo(0);

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(c.id()))).thenReturn(null);
    assertThat(store.countRatingsByDoctor(c.id())).isEqualTo(0);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenReturn(List.of());
    assertThat(store.doctorPeriodStats(c.id(), now, now.plusSeconds(1)).consultsPeriod())
        .isEqualTo(0);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any()))
        .thenReturn(null);
    assertThat(store.doctorPeriodStats(c.id(), now, now.plusSeconds(1)).consultsPeriod())
        .isEqualTo(0);

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getString("patient_name")).thenReturn("Ravi");
              when(rs.getString("patient_phone")).thenReturn("+91-9");
              when(rs.getString("doctor_name")).thenReturn(null);
              when(rs.getString("medicines_needing_rx"))
                  .thenReturn(
                      "[{\"name\":null,\"reason\":\"REFILL\"},{\"name\":\"  \",\"reason\":\"REFILL\"},{\"name\":\"Ok\",\"reason\":\"REFILL\"}]");
              when(rs.getTimestamp("call_started_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listActiveQueue().get(0).medicinesRequested()).containsExactly("Ok");
  }

  @Test
  void mapRowParsesJsonAndNulls() throws Exception {
    Consult c = sample(Ids.newId());
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getObject("customer_id")).thenReturn(c.customerId());
              when(rs.getObject("doctor_id")).thenReturn(null);
              when(rs.getString("patient_name")).thenReturn(c.patientName());
              when(rs.getString("patient_phone")).thenReturn(c.patientPhone());
              when(rs.getString("slot_type")).thenReturn(c.slotType());
              when(rs.getTimestamp("scheduled_at")).thenReturn(null);
              when(rs.getString("symptoms")).thenReturn(null);
              when(rs.getString("medicines_needing_rx")).thenReturn("[]");
              when(rs.getObject("cart_id")).thenReturn(null);
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              when(rs.getString("reason")).thenReturn("GENERAL");
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getTimestamp("call_started_at")).thenReturn(null);
              when(rs.getTimestamp("call_ended_at")).thenReturn(null);
              when(rs.getObject("e_prescription_id")).thenReturn(null);
              when(rs.getBoolean("is_advice_only")).thenReturn(false);
              when(rs.getObject("rating")).thenReturn(5);
              when(rs.getInt("rating")).thenReturn(5);
              when(rs.getString("feedback_text")).thenReturn(null);
              when(rs.getString("auto_cancelled_reason")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("deleted_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    Consult loaded = store.findById(c.id()).orElseThrow();
    assertThat(loaded.symptoms()).isEmpty();
    assertThat(loaded.rating()).isEqualTo(5);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getObject("customer_id")).thenReturn(c.customerId());
              when(rs.getObject("doctor_id")).thenReturn(null);
              when(rs.getString("patient_name")).thenReturn("x");
              when(rs.getString("patient_phone")).thenReturn("y");
              when(rs.getString("slot_type")).thenReturn("NOW");
              when(rs.getTimestamp("scheduled_at")).thenReturn(null);
              when(rs.getString("symptoms")).thenReturn("{bad");
              when(rs.getString("medicines_needing_rx")).thenReturn(null);
              when(rs.getObject("cart_id")).thenReturn(null);
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              when(rs.getString("reason")).thenReturn("GENERAL");
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getTimestamp("call_started_at")).thenReturn(null);
              when(rs.getTimestamp("call_ended_at")).thenReturn(null);
              when(rs.getBigDecimal("duration_minutes")).thenReturn(null);
              when(rs.getObject("e_prescription_id")).thenReturn(null);
              when(rs.getBoolean("is_advice_only")).thenReturn(false);
              when(rs.getString("clinical_notes")).thenReturn(null);
              when(rs.getObject("rating")).thenReturn(null);
              when(rs.getString("feedback_text")).thenReturn(null);
              when(rs.getTimestamp("rated_at")).thenReturn(null);
              when(rs.getString("auto_cancelled_reason")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("deleted_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThatThrownBy(() -> store.findById(c.id())).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void serializeFailures() throws Exception {
    ObjectMapper failing = mock(ObjectMapper.class);
    when(failing.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});
    JdbcConsultStore bad = new JdbcConsultStore(jdbc, failing);
    assertThatThrownBy(() -> bad.insert(sample(Ids.newId())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void parseMedsInvalidJson() throws Exception {
    Consult c = sample(Ids.newId());
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getObject("customer_id")).thenReturn(c.customerId());
              when(rs.getObject("doctor_id")).thenReturn(null);
              when(rs.getString("patient_name")).thenReturn("x");
              when(rs.getString("patient_phone")).thenReturn("y");
              when(rs.getString("slot_type")).thenReturn("NOW");
              when(rs.getTimestamp("scheduled_at")).thenReturn(null);
              when(rs.getString("symptoms")).thenReturn("[]");
              when(rs.getString("medicines_needing_rx")).thenReturn("{bad");
              when(rs.getObject("cart_id")).thenReturn(null);
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              when(rs.getString("reason")).thenReturn("GENERAL");
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getTimestamp("call_started_at")).thenReturn(null);
              when(rs.getTimestamp("call_ended_at")).thenReturn(null);
              when(rs.getBigDecimal("duration_minutes")).thenReturn(null);
              when(rs.getObject("e_prescription_id")).thenReturn(null);
              when(rs.getBoolean("is_advice_only")).thenReturn(false);
              when(rs.getString("clinical_notes")).thenReturn(null);
              when(rs.getObject("rating")).thenReturn(null);
              when(rs.getString("feedback_text")).thenReturn(null);
              when(rs.getTimestamp("rated_at")).thenReturn(null);
              when(rs.getString("auto_cancelled_reason")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("deleted_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThatThrownBy(() -> store.findById(c.id())).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void listStatusFilterBranchesAndJsonEdges() throws Exception {
    Consult c = sample(Ids.newId());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.list(new ListFilter(c.customerId(), null, 1, 20)).total()).isEqualTo(0);
    assertThat(store.list(new ListFilter(c.customerId(), "  ", 1, 20)).total()).isEqualTo(0);
    assertThat(store.list(new ListFilter(c.customerId(), "ALL", 1, 20)).total()).isEqualTo(0);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getObject("e_prescription_id")).thenReturn(null);
              when(rs.getObject("cart_id")).thenReturn(null);
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              when(rs.getObject("rating")).thenReturn(null);
              when(rs.getString("doctor_name")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(
            store.list(new ListFilter(c.customerId(), "REQUESTED", 1, 20)).items().get(0).rating())
        .isNull();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getObject("avg_min")).thenReturn(0);
              return ex.extractData(rs);
            });
    assertThat(store.rollingAvgCallDurationMinutes()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getObject("customer_id")).thenReturn(c.customerId());
              when(rs.getObject("doctor_id")).thenReturn(null);
              when(rs.getString("patient_name")).thenReturn("x");
              when(rs.getString("patient_phone")).thenReturn("y");
              when(rs.getString("slot_type")).thenReturn("SCHEDULED");
              when(rs.getTimestamp("scheduled_at")).thenReturn(Timestamp.from(now));
              when(rs.getString("symptoms")).thenReturn(null);
              when(rs.getString("medicines_needing_rx")).thenReturn(null);
              when(rs.getObject("cart_id")).thenReturn(null);
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              when(rs.getString("reason")).thenReturn("GENERAL");
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getTimestamp("call_started_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("call_ended_at")).thenReturn(Timestamp.from(now));
              when(rs.getObject("e_prescription_id")).thenReturn(null);
              when(rs.getBoolean("is_advice_only")).thenReturn(false);
              when(rs.getObject("rating")).thenReturn(null);
              when(rs.getString("feedback_text")).thenReturn(null);
              when(rs.getString("auto_cancelled_reason")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    Consult nullJson = store.findById(c.id()).orElseThrow();
    assertThat(nullJson.symptoms()).isEmpty();
    assertThat(nullJson.medicinesNeedingRx()).isEmpty();
    assertThat(nullJson.scheduledAt()).isEqualTo(now);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getObject("customer_id")).thenReturn(c.customerId());
              when(rs.getObject("doctor_id")).thenReturn(null);
              when(rs.getString("patient_name")).thenReturn("x");
              when(rs.getString("patient_phone")).thenReturn("y");
              when(rs.getString("slot_type")).thenReturn("NOW");
              when(rs.getTimestamp("scheduled_at")).thenReturn(null);
              when(rs.getString("symptoms")).thenReturn("   ");
              when(rs.getString("medicines_needing_rx")).thenReturn("");
              when(rs.getObject("cart_id")).thenReturn(null);
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              when(rs.getString("reason")).thenReturn("GENERAL");
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getTimestamp("call_started_at")).thenReturn(null);
              when(rs.getTimestamp("call_ended_at")).thenReturn(null);
              when(rs.getBigDecimal("duration_minutes")).thenReturn(null);
              when(rs.getObject("e_prescription_id")).thenReturn(null);
              when(rs.getBoolean("is_advice_only")).thenReturn(false);
              when(rs.getString("clinical_notes")).thenReturn(null);
              when(rs.getObject("rating")).thenReturn(null);
              when(rs.getString("feedback_text")).thenReturn(null);
              when(rs.getTimestamp("rated_at")).thenReturn(null);
              when(rs.getString("auto_cancelled_reason")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("deleted_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(c.id()).orElseThrow().symptoms()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getObject("customer_id")).thenReturn(c.customerId());
              when(rs.getObject("doctor_id")).thenReturn(null);
              when(rs.getString("patient_name")).thenReturn("x");
              when(rs.getString("patient_phone")).thenReturn("y");
              when(rs.getString("slot_type")).thenReturn("NOW");
              when(rs.getTimestamp("scheduled_at")).thenReturn(null);
              when(rs.getString("symptoms")).thenReturn("null");
              when(rs.getString("medicines_needing_rx")).thenReturn("null");
              when(rs.getObject("cart_id")).thenReturn(null);
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              when(rs.getString("reason")).thenReturn("GENERAL");
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getTimestamp("call_started_at")).thenReturn(null);
              when(rs.getTimestamp("call_ended_at")).thenReturn(null);
              when(rs.getBigDecimal("duration_minutes")).thenReturn(null);
              when(rs.getObject("e_prescription_id")).thenReturn(null);
              when(rs.getBoolean("is_advice_only")).thenReturn(false);
              when(rs.getString("clinical_notes")).thenReturn(null);
              when(rs.getObject("rating")).thenReturn(null);
              when(rs.getString("feedback_text")).thenReturn(null);
              when(rs.getTimestamp("rated_at")).thenReturn(null);
              when(rs.getString("auto_cancelled_reason")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("deleted_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    Consult nullLists = store.findById(c.id()).orElseThrow();
    assertThat(nullLists.symptoms()).isEmpty();
    assertThat(nullLists.medicinesNeedingRx()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(c.id());
              when(rs.getObject("customer_id")).thenReturn(c.customerId());
              when(rs.getObject("doctor_id")).thenReturn(null);
              when(rs.getString("patient_name")).thenReturn("x");
              when(rs.getString("patient_phone")).thenReturn("y");
              when(rs.getString("slot_type")).thenReturn("NOW");
              when(rs.getTimestamp("scheduled_at")).thenReturn(null);
              when(rs.getString("symptoms")).thenReturn("[]");
              when(rs.getString("medicines_needing_rx"))
                  .thenReturn("[{\"name\":null,\"reason\":null}]");
              when(rs.getObject("cart_id")).thenReturn(null);
              when(rs.getBoolean("is_cart_mode")).thenReturn(false);
              when(rs.getString("reason")).thenReturn("GENERAL");
              when(rs.getString("status")).thenReturn("REQUESTED");
              when(rs.getTimestamp("call_started_at")).thenReturn(null);
              when(rs.getTimestamp("call_ended_at")).thenReturn(null);
              when(rs.getBigDecimal("duration_minutes")).thenReturn(null);
              when(rs.getObject("e_prescription_id")).thenReturn(null);
              when(rs.getBoolean("is_advice_only")).thenReturn(false);
              when(rs.getString("clinical_notes")).thenReturn(null);
              when(rs.getObject("rating")).thenReturn(null);
              when(rs.getString("feedback_text")).thenReturn(null);
              when(rs.getTimestamp("rated_at")).thenReturn(null);
              when(rs.getString("auto_cancelled_reason")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("deleted_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(c.id()).orElseThrow().medicinesNeedingRx()).hasSize(1);

    ObjectMapper failingMeds = mock(ObjectMapper.class);
    when(failingMeds.writeValueAsString(any()))
        .thenReturn("[]")
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});
    JdbcConsultStore badMeds = new JdbcConsultStore(jdbc, failingMeds);
    when(jdbc.update(anyString(), ArgumentMatchers.<Object>any())).thenReturn(1);
    assertThatThrownBy(() -> badMeds.insert(sample(Ids.newId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("medicines_needing_rx");

    Consult scheduled =
        new Consult(
            Ids.newId(),
            Ids.newId(),
            null,
            "Ravi",
            "+91-9",
            Consult.SLOT_SCHEDULED,
            now.plusSeconds(3600),
            List.of(),
            List.of(),
            null,
            false,
            "GENERAL",
            Consult.STATUS_REQUESTED,
            now,
            now,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    store.insert(scheduled);

    assertThat(new ConsultStore.Page(null, 0).items()).isEmpty();
  }

  private Consult sample(UUID id) {
    return new Consult(
        id,
        Ids.newId(),
        null,
        "Ravi",
        "+91-9",
        Consult.SLOT_NOW,
        null,
        List.of("fatigue"),
        List.of(new MedicineNeed("Metformin", "REFILL")),
        Ids.newId(),
        true,
        "RX_NEEDED",
        Consult.STATUS_REQUESTED,
        null,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        now,
        now,
        null);
  }

  private Consult mapFrom(RowMapper<Consult> mapper, Consult c) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(c.id());
    when(rs.getObject("customer_id")).thenReturn(c.customerId());
    when(rs.getObject("doctor_id")).thenReturn(c.doctorId());
    when(rs.getString("patient_name")).thenReturn(c.patientName());
    when(rs.getString("patient_phone")).thenReturn(c.patientPhone());
    when(rs.getString("slot_type")).thenReturn(c.slotType());
    when(rs.getTimestamp("scheduled_at")).thenReturn(null);
    when(rs.getString("symptoms")).thenReturn(om.writeValueAsString(c.symptoms()));
    when(rs.getString("medicines_needing_rx"))
        .thenReturn("[{\"name\":\"Metformin\",\"reason\":\"REFILL\"}]");
    when(rs.getObject("cart_id")).thenReturn(c.cartId());
    when(rs.getBoolean("is_cart_mode")).thenReturn(c.cartMode());
    when(rs.getString("reason")).thenReturn(c.reason());
    when(rs.getString("status")).thenReturn(c.status());
    when(rs.getTimestamp("call_started_at")).thenReturn(null);
    when(rs.getTimestamp("call_ended_at")).thenReturn(null);
    when(rs.getBigDecimal("duration_minutes")).thenReturn(null);
    when(rs.getObject("e_prescription_id")).thenReturn(null);
    when(rs.getBoolean("is_advice_only")).thenReturn(false);
    when(rs.getString("clinical_notes")).thenReturn(null);
    when(rs.getObject("rating")).thenReturn(null);
    when(rs.getString("feedback_text")).thenReturn(null);
    when(rs.getTimestamp("rated_at")).thenReturn(null);
    when(rs.getString("auto_cancelled_reason")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(c.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(c.updatedAt()));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    return mapper.mapRow(rs, 0);
  }
}
