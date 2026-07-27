package com.nammamedmate.pharmacy.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyController;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyController.ApproveRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyController.ReactivateRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyController.RejectRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyController.RequestDocumentsRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyController.SuspendRequest;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcAdminPharmacyStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcAuditLogStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcZoneStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyStatusService;
import com.nammamedmate.pharmacy.application.AdminPharmacyStatusService.AdminListResult;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminListRow;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.ZoneRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminPharmacyStatusAdapterCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:10:00Z");
  private static final UUID PID = Ids.newId();
  private static final UUID ZONE = UUID.fromString("a0000001-0000-4000-8000-000000000001");

  @Test
  void controllerDelegatesAllEndpoints() {
    AdminPharmacyStatusService service = mock(AdminPharmacyStatusService.class);
    when(service.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new AdminListResult(Map.of("pharmacies", List.of()), PaginationMeta.of(1, 50, 0)));
    when(service.detail(any(), any())).thenReturn(Map.of("pharmacy_id", PID.toString()));
    when(service.approve(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "ACTIVE"));
    when(service.reject(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "REJECTED"));
    when(service.suspend(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "SUSPENDED"));
    when(service.reactivate(any(), any(), any(), any())).thenReturn(Map.of("status", "ACTIVE"));
    when(service.requestDocuments(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("message", "ok"));

    AdminPharmacyController controller = new AdminPharmacyController(service);
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    MockHttpServletRequest http = new MockHttpServletRequest();
    http.setRemoteAddr("127.0.0.1");

    assertThat(
            controller.list(principal, "ALL", null, null, null, null, null, null, 1, 50).success())
        .isTrue();
    assertThat(controller.detail(principal, PID).success()).isTrue();
    assertThat(
            controller
                .approve(
                    principal, PID, new ApproveRequest(new BigDecimal("8.00"), ZONE, "n"), http)
                .success())
        .isTrue();
    assertThat(controller.approve(principal, PID, null, http).success()).isTrue();
    assertThat(
            controller.reject(principal, PID, new RejectRequest("bad", null, true), http).success())
        .isTrue();
    assertThat(controller.reject(principal, PID, null, http).success()).isTrue();
    assertThat(
            controller
                .suspend(principal, PID, new SuspendRequest("r", "TEMPORARY", null), http)
                .success())
        .isTrue();
    assertThat(controller.suspend(principal, PID, null, http).success()).isTrue();
    assertThat(
            controller.reactivate(principal, PID, new ReactivateRequest("notes"), http).success())
        .isTrue();
    assertThat(controller.reactivate(principal, PID, null, http).success()).isTrue();
    assertThat(
            controller
                .requestDocuments(
                    principal, PID, new RequestDocumentsRequest(List.of("PAN_CARD"), "msg"), http)
                .success())
        .isTrue();
    assertThat(controller.requestDocuments(principal, PID, null, http).success()).isTrue();

    // SpotBugs defensive copies — null branches
    assertThat(new AdminListResult(null, PaginationMeta.of(1, 1, 0)).data()).isEmpty();
    assertThat(new RequestDocumentsRequest(null, "m").documentTypes()).isNull();
    assertThat(new RequestDocumentsRequest(List.of("PAN_CARD"), "m").documentTypes())
        .containsExactly("PAN_CARD");

    MockHttpServletRequest blankIp = new MockHttpServletRequest();
    blankIp.setRemoteAddr("  ");
    assertThat(
            controller
                .approve(
                    principal, PID, new ApproveRequest(new BigDecimal("8"), ZONE, null), blankIp)
                .success())
        .isTrue();
    MockHttpServletRequest nullIp = new MockHttpServletRequest();
    nullIp.setRemoteAddr(null);
    assertThat(
            controller
                .approve(
                    principal, PID, new ApproveRequest(new BigDecimal("8"), ZONE, null), nullIp)
                .success())
        .isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcStoresCoverBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();

    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);
    JdbcAdminPharmacyStore store = new JdbcAdminPharmacyStore(jdbc, mapper);
    assertThat(store.nextCode()).isEqualTo("PHM-0042");

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapperFn = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(PID);
              when(rs.getString("code")).thenReturn("PHM-0001");
              when(rs.getString("business_name")).thenReturn(null);
              when(rs.getString("name")).thenReturn("Fallback");
              when(rs.getString("owner_name")).thenReturn("O");
              when(rs.getString("phone")).thenReturn("+91");
              when(rs.getString("zone_name")).thenReturn("Z");
              when(rs.getString("status")).thenReturn("KYC_SUBMITTED");
              when(rs.getString("plan")).thenReturn("FREE");
              when(rs.getBoolean("is_online")).thenReturn(false);
              when(rs.getTimestamp("kyc_submitted_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("kyc_sla_reset_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("auto_kyc_status")).thenReturn("PASS");
              when(rs.getString("email")).thenReturn("e@x.com");
              when(rs.getString("business_type")).thenReturn("PHARMACY");
              when(rs.getString("address")).thenReturn("{\"city\":\"X\"}");
              when(rs.getString("gstin")).thenReturn("g");
              when(rs.getString("drug_licence_number")).thenReturn("d");
              when(rs.getString("fssai_number")).thenReturn(null);
              when(rs.getString("pan_number")).thenReturn("p");
              when(rs.getBigDecimal("commission_pct")).thenReturn(null);
              when(rs.getObject("zone_id")).thenReturn(ZONE);
              when(rs.getBoolean("can_reapply")).thenReturn(true);
              when(rs.getString("rejection_reason")).thenReturn(null);
              when(rs.getString("rejection_details")).thenReturn(null);
              when(rs.getTimestamp("activated_at")).thenReturn(null);
              when(rs.getTimestamp("suspended_at")).thenReturn(null);
              when(rs.getString("suspend_type")).thenReturn(null);
              when(rs.getString("document_type")).thenReturn("PAN_CARD");
              when(rs.getString("status")).thenReturn("UPLOADED");
              Object row = mapperFn.mapRow(rs, 0);
              return List.of(row);
            });

    var page =
        store.list(
            new com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.ListFilter(
                "KYC_SUBMITTED", ZONE, "FREE", false, "search", "submitted_at", "asc", 10, 0));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows()).hasSize(1);

    store.list(
        new com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.ListFilter(
            "ALL", null, null, null, null, "business_name", "desc", 10, 0));
    store.list(
        new com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.ListFilter(
            null, null, null, null, "  ", "created_at", "asc", 10, 0));

    AdminDetailRow detail = store.findDetail(PID).orElseThrow();
    assertThat(detail.businessName()).isEqualTo("Fallback");
    assertThat(detail.commissionPct()).isEqualByComparingTo("8.00");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(PID)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapperFn = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("document_type")).thenReturn("PAN_CARD");
              when(rs.getString("status")).thenReturn("VERIFIED");
              return List.of(mapperFn.mapRow(rs, 0));
            });
    assertThat(store.documentStatusSummary(PID)).containsEntry("PAN_CARD", "VERIFIED");

    Instant at = NOW;
    store.approve(PID, new BigDecimal("8.00"), ZONE, at, at);
    store.reject(PID, "r", "d", true, at);
    store.suspend(PID, "TEMPORARY", true, at);
    store.reactivate(PID, at, true);
    store.resetKycSla(PID, at);
    verify(jdbc, org.mockito.Mockito.atLeast(5)).update(anyString(), any(Object[].class));

    // blank address / blank json
    when(jdbc.query(anyString(), any(RowMapper.class), eq(PID)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapperFn = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(PID);
              when(rs.getString("code")).thenReturn("PHM-1");
              when(rs.getString("business_name")).thenReturn("B");
              when(rs.getString("name")).thenReturn("N");
              when(rs.getString("owner_name")).thenReturn("O");
              when(rs.getString("phone")).thenReturn("p");
              when(rs.getString("email")).thenReturn("e");
              when(rs.getString("business_type")).thenReturn("PHARMACY");
              when(rs.getString("address")).thenReturn(" ");
              when(rs.getString("gstin")).thenReturn(null);
              when(rs.getString("drug_licence_number")).thenReturn(null);
              when(rs.getString("fssai_number")).thenReturn(null);
              when(rs.getString("pan_number")).thenReturn(null);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("plan")).thenReturn("FREE");
              when(rs.getBigDecimal("commission_pct")).thenReturn(new BigDecimal("9.00"));
              when(rs.getObject("zone_id")).thenReturn(null);
              when(rs.getBoolean("is_online")).thenReturn(true);
              when(rs.getBoolean("can_reapply")).thenReturn(true);
              when(rs.getTimestamp("kyc_submitted_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("rejection_reason")).thenReturn(null);
              when(rs.getString("rejection_details")).thenReturn(null);
              when(rs.getTimestamp("activated_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("suspended_at")).thenReturn(null);
              when(rs.getString("suspend_type")).thenReturn(null);
              when(rs.getTimestamp("kyc_sla_reset_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapperFn.mapRow(rs, 0));
            });
    assertThat(store.findDetail(PID).orElseThrow().address()).isEmpty();

    JdbcZoneStore zones = new JdbcZoneStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(ZONE)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapperFn = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ZONE);
              when(rs.getString("name")).thenReturn("Koramangala Zone");
              when(rs.getBoolean("active")).thenReturn(true);
              return List.of(mapperFn.mapRow(rs, 0));
            });
    ZoneRecord z = zones.findById(ZONE).orElseThrow();
    assertThat(z.name()).isEqualTo("Koramangala Zone");

    JdbcAuditLogStore audit = new JdbcAuditLogStore(jdbc, mapper);
    audit.append(
        new AuditLogRecord(
            Ids.newId(),
            "PHARMACY",
            PID,
            "KYC_APPROVED",
            Ids.newId(),
            "ADMIN_OPERATIONS",
            Map.of("x", 1),
            "  ",
            NOW));
    audit.append(
        new AuditLogRecord(
            Ids.newId(), "PHARMACY", PID, "KYC_REJECTED", null, "ADMIN_SUPER", null, null, NOW));
    verify(jdbc, org.mockito.Mockito.atLeast(2))
        .update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void listRowUsesSlaResetAsAgeAnchor() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcAdminPharmacyStore store = new JdbcAdminPharmacyStore(jdbc, new ObjectMapper());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapperFn = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(PID);
              when(rs.getString("code")).thenReturn("PHM-1");
              when(rs.getString("business_name")).thenReturn("B");
              when(rs.getString("name")).thenReturn("N");
              when(rs.getString("owner_name")).thenReturn("O");
              when(rs.getString("phone")).thenReturn("p");
              when(rs.getString("zone_name")).thenReturn(null);
              when(rs.getString("status")).thenReturn("KYC_SUBMITTED");
              when(rs.getString("plan")).thenReturn("FREE");
              when(rs.getBoolean("is_online")).thenReturn(false);
              when(rs.getTimestamp("kyc_submitted_at"))
                  .thenReturn(Timestamp.from(NOW.minusSeconds(10000)));
              when(rs.getTimestamp("kyc_sla_reset_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("auto_kyc_status")).thenReturn(null);
              AdminListRow row = (AdminListRow) mapperFn.mapRow(rs, 0);
              assertThat(row.ageAnchor()).isEqualTo(NOW);
              return List.of(row);
            });
    store.list(
        new com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.ListFilter(
            "KYC_SUBMITTED", null, null, null, null, "created_at", "desc", 5, 0));
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcEdgeBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    JdbcAdminPharmacyStore store = new JdbcAdminPharmacyStore(jdbc, mapper);

    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    assertThat(store.nextCode()).isEqualTo("PHM-0001");

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapperFn = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(PID);
              when(rs.getString("code")).thenReturn("PHM-1");
              when(rs.getString("business_name")).thenReturn("  ");
              when(rs.getString("name")).thenReturn("NameFallback");
              when(rs.getString("owner_name")).thenReturn("O");
              when(rs.getString("phone")).thenReturn("p");
              when(rs.getString("zone_name")).thenReturn(null);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("plan")).thenReturn("FREE");
              when(rs.getBoolean("is_online")).thenReturn(true);
              when(rs.getTimestamp("kyc_submitted_at")).thenReturn(null);
              when(rs.getTimestamp("kyc_sla_reset_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("auto_kyc_status")).thenReturn(null);
              AdminListRow row = (AdminListRow) mapperFn.mapRow(rs, 0);
              assertThat(row.businessName()).isEqualTo("NameFallback");
              return List.of(row);
            });
    assertThat(
            store
                .list(
                    new com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore
                        .ListFilter("ALL", null, "  ", null, null, null, "asc", 5, 0))
                .total())
        .isEqualTo(0);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(PID)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapperFn = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(PID);
              when(rs.getString("code")).thenReturn("PHM-1");
              when(rs.getString("business_name")).thenReturn("");
              when(rs.getString("name")).thenReturn("N");
              when(rs.getString("owner_name")).thenReturn("O");
              when(rs.getString("phone")).thenReturn("p");
              when(rs.getString("email")).thenReturn("e");
              when(rs.getString("business_type")).thenReturn("PHARMACY");
              when(rs.getString("address")).thenReturn(null);
              when(rs.getString("gstin")).thenReturn(null);
              when(rs.getString("drug_licence_number")).thenReturn(null);
              when(rs.getString("fssai_number")).thenReturn(null);
              when(rs.getString("pan_number")).thenReturn(null);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("plan")).thenReturn("FREE");
              when(rs.getBigDecimal("commission_pct")).thenReturn(new BigDecimal("8.00"));
              when(rs.getObject("zone_id")).thenReturn(null);
              when(rs.getBoolean("is_online")).thenReturn(true);
              when(rs.getBoolean("can_reapply")).thenReturn(true);
              when(rs.getTimestamp("kyc_submitted_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("rejection_reason")).thenReturn(null);
              when(rs.getString("rejection_details")).thenReturn(null);
              when(rs.getTimestamp("activated_at")).thenReturn(null);
              when(rs.getTimestamp("suspended_at")).thenReturn(null);
              when(rs.getString("suspend_type")).thenReturn(null);
              when(rs.getTimestamp("kyc_sla_reset_at")).thenReturn(null);
              AdminDetailRow row = (AdminDetailRow) mapperFn.mapRow(rs, 0);
              assertThat(row.businessName()).isEqualTo("N");
              assertThat(row.address()).isEmpty();
              return List.of(row);
            });
    store.findDetail(PID);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(PID)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapperFn = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(PID);
              when(rs.getString("code")).thenReturn("PHM-1");
              when(rs.getString("business_name")).thenReturn("B");
              when(rs.getString("name")).thenReturn("N");
              when(rs.getString("owner_name")).thenReturn("O");
              when(rs.getString("phone")).thenReturn("p");
              when(rs.getString("email")).thenReturn("e");
              when(rs.getString("business_type")).thenReturn("PHARMACY");
              when(rs.getString("address")).thenReturn("{bad");
              when(rs.getString("gstin")).thenReturn(null);
              when(rs.getString("drug_licence_number")).thenReturn(null);
              when(rs.getString("fssai_number")).thenReturn(null);
              when(rs.getString("pan_number")).thenReturn(null);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("plan")).thenReturn("FREE");
              when(rs.getBigDecimal("commission_pct")).thenReturn(new BigDecimal("8.00"));
              when(rs.getObject("zone_id")).thenReturn(null);
              when(rs.getBoolean("is_online")).thenReturn(true);
              when(rs.getBoolean("can_reapply")).thenReturn(true);
              when(rs.getTimestamp("kyc_submitted_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("rejection_reason")).thenReturn(null);
              when(rs.getString("rejection_details")).thenReturn(null);
              when(rs.getTimestamp("activated_at")).thenReturn(null);
              when(rs.getTimestamp("suspended_at")).thenReturn(null);
              when(rs.getString("suspend_type")).thenReturn(null);
              when(rs.getTimestamp("kyc_sla_reset_at")).thenReturn(null);
              return List.of(mapperFn.mapRow(rs, 0));
            });
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.findDetail(PID))
        .isInstanceOf(IllegalStateException.class);

    ObjectMapper broken = mock(ObjectMapper.class);
    when(broken.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    JdbcAuditLogStore auditBroken = new JdbcAuditLogStore(jdbc, broken);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                auditBroken.append(
                    new AuditLogRecord(
                        Ids.newId(),
                        "PHARMACY",
                        PID,
                        "X",
                        null,
                        "ADMIN_SUPER",
                        Map.of("a", 1),
                        "1.2.3.4",
                        NOW)))
        .isInstanceOf(IllegalStateException.class);

    JdbcAuditLogStore auditOk = new JdbcAuditLogStore(jdbc, mapper);
    auditOk.append(
        new AuditLogRecord(
            Ids.newId(), "PHARMACY", PID, "X", null, "ADMIN_SUPER", Map.of(), "1.2.3.4", NOW));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(ZONE))).thenReturn(List.of());
    assertThat(new JdbcZoneStore(jdbc).findById(ZONE)).isEmpty();
  }
}
