package com.nammamedmate.settings.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.AdminStaffRow;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.AuditTrailEntry;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.InviterRef;
import com.nammamedmate.settings.application.port.out.AdminStaffStore.PageResult;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAdminStaffStoreTest {

  private JdbcTemplate jdbc;
  private JdbcAdminStaffStore store;
  private final Instant now = Instant.parse("2026-07-24T02:00:00Z");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    store = new JdbcAdminStaffStore(jdbc, new ObjectMapper());
  }

  @Test
  void findByIdPresentAndEmpty() throws Exception {
    UUID id = Ids.newId();
    stubStaffQuery(id);
    assertThat(store.findById(id))
        .isPresent()
        .get()
        .extracting(AdminStaffRow::email)
        .isEqualTo("e@t.in");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of());
    assertThat(store.findById(id)).isEmpty();
  }

  @Test
  void findByEmailAndInviter() throws Exception {
    UUID id = Ids.newId();
    stubStaffQuery(id);
    assertThat(store.findByEmail("e@t.in")).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("name")).thenReturn("N");
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findInviter(id)).contains(new InviterRef(id, "N"));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of());
    assertThat(store.findInviter(id)).isEmpty();
  }

  @Test
  void emailExistsAndCountActiveSuper() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq("e@t.in"))).thenReturn(1L);
    assertThat(store.emailExists("e@t.in")).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq("z@t.in"))).thenReturn(0L);
    assertThat(store.emailExists("z@t.in")).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq("x@t.in"))).thenReturn(null);
    assertThat(store.emailExists("x@t.in")).isFalse();

    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
    assertThat(store.countActiveSuperAdmins()).isEqualTo(3L);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    assertThat(store.countActiveSuperAdmins()).isZero();
  }

  @Test
  void listFiltersAndEmptyTotal() throws Exception {
    UUID id = Ids.newId();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockStaffRs(id), 0));
            });
    PageResult page = store.list("admin_finance", "ACTIVE", "fin", 2, 10);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.list(null, null, null, 1, 20).total()).isZero();
  }

  @Test
  void mutationsCallUpdate() {
    UUID id = Ids.newId();
    store.insertInvited(id, "N", "e@t.in", "admin_support", id, "hash", now, now);
    store.refreshInvite(id, "N2", "admin_finance", id, "hash2", now, now);
    store.update(id, "N2", "admin_finance", "SUSPENDED", now);
    store.softDelete(id, now);
    store.setResetToken(id, "rh", now, now);
    verify(jdbc, times(5)).update(anyString(), any(Object[].class));
  }

  @Test
  void listAuditTrailBranches() throws Exception {
    UUID id = Ids.newId();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(mapAudit(inv, fullPayload(), "staff.role_changed", "Super")));
    List<AuditTrailEntry> full = store.listAuditTrail(id);
    assertThat(full.get(0).from()).isEqualTo("admin_support");
    assertThat(full.get(0).to()).isEqualTo("admin_operations");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(mapAudit(inv, "not-json", "staff.invited", null)));
    assertThat(store.listAuditTrail(id).get(0).from()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv ->
                List.of(
                    mapAudit(
                        inv,
                        "{\"before\":{\"status\":\"ACTIVE\"},\"after\":{\"status\":\"SUSPENDED\"}}",
                        "staff.status_changed",
                        "A")));
    assertThat(store.listAuditTrail(id).get(0).from()).isEqualTo("ACTIVE");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv ->
                List.of(
                    mapAudit(
                        inv,
                        "{\"before\":{\"name\":\"Old\"},\"after\":{\"name\":\"New\"}}",
                        "staff.name_changed",
                        "A")));
    assertThat(store.listAuditTrail(id).get(0).to()).isEqualTo("New");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(mapAudit(inv, null, "staff.invited", "A")));
    assertThat(store.listAuditTrail(id).get(0).action()).isEqualTo("staff.invited");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(mapAudit(inv, "   ", "staff.invited", "A")));
    assertThat(store.listAuditTrail(id).get(0).from()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(mapAudit(inv, "{}", "staff.invited", "A")));
    assertThat(store.listAuditTrail(id).get(0).from()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv ->
                List.of(
                    mapAudit(
                        inv,
                        "{\"before\":{\"role\":null,\"status\":null,\"name\":null},\"after\":{}}",
                        "staff.invited",
                        "A")));
    assertThat(store.listAuditTrail(id).get(0).from()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv ->
                List.of(mapAudit(inv, "{\"before\":\"x\",\"after\":\"y\"}", "staff.invited", "A")));
    assertThat(store.listAuditTrail(id).get(0).from()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv ->
                List.of(
                    mapAudit(
                        inv,
                        "{\"before\":{\"from\":\"admin_support\"},\"after\":{\"to\":\"admin_operations\"}}",
                        "staff.role_changed",
                        "A")));
    assertThat(store.listAuditTrail(id).get(0).from()).isEqualTo("admin_support");
    assertThat(store.listAuditTrail(id).get(0).to()).isEqualTo("admin_operations");
  }

  private void stubStaffQuery(UUID id) {
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockStaffRs(id), 0));
            });
  }

  private ResultSet mockStaffRs(UUID id) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("name")).thenReturn("N");
    when(rs.getString("email")).thenReturn("e@t.in");
    when(rs.getString("role")).thenReturn("admin_support");
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getBoolean("mfa_enabled")).thenReturn(false);
    when(rs.getTimestamp("last_active_at")).thenReturn(null);
    when(rs.getObject("invited_by")).thenReturn(null);
    when(rs.getTimestamp("invite_expires_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    return rs;
  }

  private Object mapAudit(
      org.mockito.invocation.InvocationOnMock inv, String payload, String action, String actor)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("payload")).thenReturn(payload);
    when(rs.getString("action")).thenReturn(action);
    when(rs.getString("actor_name")).thenReturn(actor);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    RowMapper<?> mapper = inv.getArgument(1);
    return mapper.mapRow(rs, 0);
  }

  private static String fullPayload() {
    return "{\"before\":{\"role\":\"admin_support\"},\"after\":{\"role\":\"admin_operations\"},"
        + "\"from\":\"admin_support\",\"to\":\"admin_operations\"}";
  }
}
