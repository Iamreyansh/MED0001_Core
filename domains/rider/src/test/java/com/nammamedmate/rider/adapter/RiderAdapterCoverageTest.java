package com.nammamedmate.rider.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.rider.adapter.in.web.AdminFleetController;
import com.nammamedmate.rider.adapter.in.web.AdminRiderController;
import com.nammamedmate.rider.adapter.in.web.RiderKycController;
import com.nammamedmate.rider.adapter.in.web.RiderRegisterController;
import com.nammamedmate.rider.adapter.in.web.RiderStatusController;
import com.nammamedmate.rider.adapter.out.cache.RedisRiderLiveStatusCache;
import com.nammamedmate.rider.adapter.out.client.StubAadhaarKycAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcActiveDeliveryAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderFleetStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderKycDocumentStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderShiftStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderStatusAuditStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcZoneLookupAdapter;
import com.nammamedmate.rider.adapter.out.storage.LocalRiderObjectStore;
import com.nammamedmate.rider.adapter.out.storage.S3RiderObjectStore;
import com.nammamedmate.rider.application.AdminFleetService;
import com.nammamedmate.rider.application.AdminFleetService.FleetResult;
import com.nammamedmate.rider.application.AdminRiderService;
import com.nammamedmate.rider.application.AdminRiderService.ListResult;
import com.nammamedmate.rider.application.RiderKycService;
import com.nammamedmate.rider.application.RiderRegistrationService;
import com.nammamedmate.rider.application.RiderStatusService;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore.DocumentRecord;
import com.nammamedmate.rider.application.port.out.RiderShiftStore.ShiftRecord;
import com.nammamedmate.rider.application.port.out.RiderStatusAuditStore.AuditRecord;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.nio.file.Files;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

class RiderAdapterCoverageTest {

  @TempDir java.nio.file.Path tmp;

  @Test
  void localAndS3ObjectStores() throws Exception {
    LocalRiderObjectStore local = new LocalRiderObjectStore(tmp);
    local.put("a/b.pdf", new byte[] {1, 2}, "application/pdf");
    assertThat(Files.exists(tmp.resolve("a-b.pdf"))).isTrue();
    local.delete("a/b.pdf");

    S3Client s3 = mock(S3Client.class);
    S3RiderObjectStore remote = new S3RiderObjectStore(s3, "bucket");
    remote.put("k", new byte[] {9}, "application/pdf");
    remote.delete("k");
    verify(s3)
        .putObject(
            any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
            any(software.amazon.awssdk.core.sync.RequestBody.class));
    verify(s3)
        .deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
  }

  @Test
  void stubAadhaar() {
    assertThat(new StubAadhaarKycAdapter().verify(Ids.newId(), "x")).isTrue();
    assertThat(new StubAadhaarKycAdapter().verify(null, "x")).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcStores() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(0L);

    JdbcRiderStore riders = new JdbcRiderStore(jdbc);
    Instant now = Instant.parse("2026-07-24T01:00:00Z");
    RiderRecord r =
        new RiderRecord(
            Ids.newId(),
            "N",
            "+919999900001",
            null,
            "BIKE",
            "KA01AB1234",
            null,
            "PENDING_KYC",
            "NOT_SUBMITTED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            0,
            null,
            0L,
            0L,
            0,
            null,
            null,
            null,
            now,
            now);
    riders.insert(r);
    riders.update(r);
    assertThat(riders.findById(r.id())).isEmpty();
    assertThat(riders.findByPhone(r.phone())).isEmpty();
    assertThat(riders.existsByPhone(r.phone())).isFalse();
    assertThat(riders.list(new ListFilter(null, "created_at", "asc", 1, 20)).total()).isZero();
    assertThat(riders.list(new ListFilter("PENDING_KYC", "name", "desc", 1, 20)).total()).isZero();
    assertThat(riders.list(new ListFilter("ACTIVE", "submitted_at", "asc", 1, 20)).total())
        .isZero();

    JdbcRiderKycDocumentStore docs = new JdbcRiderKycDocumentStore(jdbc);
    DocumentRecord d =
        new DocumentRecord(
            Ids.newId(),
            r.id(),
            "PAN",
            null,
            "key",
            "url",
            1,
            "application/pdf",
            LocalDate.parse("2029-01-01"),
            false,
            "PENDING",
            null,
            now,
            null,
            null);
    docs.insert(d);
    docs.softDelete(d.id(), now);
    assertThat(docs.findActiveByRiderAndType(r.id(), "PAN")).isEmpty();
    assertThat(docs.findActiveByRider(r.id())).isEmpty();
    assertThat(docs.countUploadsByRiderAndType(r.id(), "PAN")).isZero();
    assertThat(docs.findDueForExpiryAlert(LocalDate.now(), LocalDate.now().minusDays(1))).isEmpty();
    docs.markExpiryAlertSent(d.id());

    JdbcZoneLookupAdapter zones = new JdbcZoneLookupAdapter(jdbc);
    assertThat(zones.findById(Ids.newId())).isEmpty();

    // exercise row mappers via Answer
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              UUID id = Ids.newId();
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("name")).thenReturn("N");
              when(rs.getString("phone")).thenReturn("+919999900001");
              when(rs.getString("email")).thenReturn(null);
              when(rs.getString("vehicle_type")).thenReturn("BIKE");
              when(rs.getString("vehicle_plate_number")).thenReturn("KA01AB1234");
              when(rs.getObject("primary_zone_id")).thenReturn(null);
              when(rs.getString("status")).thenReturn("PENDING_KYC");
              when(rs.getString("kyc_status")).thenReturn("NOT_SUBMITTED");
              when(rs.getTimestamp(anyString())).thenReturn(Timestamp.from(now));
              when(rs.getObject("kyc_reviewed_by")).thenReturn(null);
              when(rs.getString("kyc_rejection_reason")).thenReturn(null);
              when(rs.getString("kyc_rejection_notes")).thenReturn(null);
              when(rs.getBoolean("aadhaar_verified")).thenReturn(false);
              when(rs.getObject("avg_rating")).thenReturn(null);
              when(rs.getInt("total_trips")).thenReturn(0);
              when(rs.getObject("on_time_pct")).thenReturn(null);
              when(rs.getLong(anyString())).thenReturn(0L);
              when(rs.getInt("daily_streak_days")).thenReturn(0);
              when(rs.getString("blocked_reason")).thenReturn(null);
              when(rs.getObject("blocked_by")).thenReturn(null);
              when(rs.getObject("rider_id")).thenReturn(id);
              when(rs.getString("document_type")).thenReturn("PAN");
              when(rs.getString("document_number")).thenReturn(null);
              when(rs.getString("file_key")).thenReturn("k");
              when(rs.getString("file_url")).thenReturn("u");
              when(rs.getInt("file_size_bytes")).thenReturn(1);
              when(rs.getString("mime_type")).thenReturn("application/pdf");
              when(rs.getDate("expiry_date")).thenReturn(Date.valueOf("2029-01-01"));
              when(rs.getBoolean("expiry_alert_sent")).thenReturn(false);
              when(rs.getString("verification_status")).thenReturn("PENDING");
              when(rs.getString("rejection_reason")).thenReturn(null);
              when(rs.getObject("reviewed_by")).thenReturn(null);
              when(rs.getBoolean("active")).thenReturn(true);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(riders.findById(Ids.newId())).isPresent();
    assertThat(docs.findActiveByRider(Ids.newId())).hasSize(1);
    assertThat(zones.findById(Ids.newId())).isPresent();
  }

  @Test
  void controllersDelegate() throws Exception {
    RiderRegistrationService reg = mock(RiderRegistrationService.class);
    when(reg.register(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("rider_id", Ids.newId().toString()));
    RiderRegisterController regCtrl = new RiderRegisterController(reg);
    ResponseEntity<?> created =
        regCtrl.register(
            new RiderRegisterController.RegisterRequest(
                "A", "9876543210", null, "BIKE", "KA01AB1234", null));
    assertThat(created.getStatusCode().value()).isEqualTo(201);

    RiderKycService kyc = mock(RiderKycService.class);
    when(kyc.uploadDocument(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("document_id", "x"));
    when(kyc.submitKyc(any())).thenReturn(Map.of("kyc_status", "SUBMITTED"));
    RiderKycController kycCtrl = new RiderKycController(kyc);
    MedmatePrincipal p =
        new MedmatePrincipal(Ids.newId(), AuthRole.RIDER, null, TokenScope.FULL, "j");
    assertThat(
            kycCtrl
                .upload(
                    p,
                    "PAN",
                    new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1}),
                    null,
                    null)
                .success())
        .isTrue();
    assertThat(kycCtrl.submit(p, new RiderKycController.EmptyBody()).success()).isTrue();

    AdminRiderService admin = mock(AdminRiderService.class);
    when(admin.list(any(), any(), any(), any(), any(), any()))
        .thenReturn(new ListResult(Map.of("riders", List.of()), null));
    when(admin.approve(any(), any(), any())).thenReturn(Map.of("status", "ACTIVE"));
    when(admin.reject(any(), any(), any(), any())).thenReturn(Map.of("kyc_status", "REJECTED"));
    when(admin.block(any(), any(), any(), any())).thenReturn(Map.of("status", "BLOCKED"));
    when(admin.unblock(any(), any(), any())).thenReturn(Map.of("status", "ACTIVE"));
    AdminRiderController adminCtrl = new AdminRiderController(admin);
    MedmatePrincipal a =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    UUID id = Ids.newId();
    assertThat(adminCtrl.list(a, null, null, null, 1, 20).success()).isTrue();
    assertThat(adminCtrl.approve(a, id, new AdminRiderController.ApproveRequest("n")).success())
        .isTrue();
    assertThat(adminCtrl.reject(a, id, new AdminRiderController.RejectRequest("R", "n")).success())
        .isTrue();
    assertThat(adminCtrl.block(a, id, new AdminRiderController.BlockRequest("R", "n")).success())
        .isTrue();
    assertThat(adminCtrl.unblock(a, id, new AdminRiderController.UnblockRequest("n")).success())
        .isTrue();

    RiderStatusService statusSvc = mock(RiderStatusService.class);
    when(statusSvc.setStatus(any(), any(), any())).thenReturn(Map.of("status", "ONLINE"));
    when(statusSvc.getStatus(any())).thenReturn(Map.of("status", "ONLINE"));
    RiderStatusController statusCtrl = new RiderStatusController(statusSvc);
    assertThat(
            statusCtrl
                .setStatus(p, new RiderStatusController.StatusRequest("ONLINE", null))
                .success())
        .isTrue();
    assertThat(statusCtrl.setStatus(p, null).success()).isTrue();
    assertThat(statusCtrl.getStatus(p).success()).isTrue();

    AdminFleetService fleetSvc = mock(AdminFleetService.class);
    when(fleetSvc.fleetOverview(any(), any(), any(), any(), any()))
        .thenReturn(new FleetResult(Map.of("riders", List.of()), null));
    when(fleetSvc.zoneRiders(any(), any())).thenReturn(Map.of("coverage_status", "COVERED"));
    when(fleetSvc.forceStatus(any(), any(), any(), any())).thenReturn(Map.of("status", "OFFLINE"));
    when(fleetSvc.reassignZone(any(), any(), any(), any()))
        .thenReturn(Map.of("rider_notified", true));
    AdminFleetController fleetCtrl = new AdminFleetController(fleetSvc);
    assertThat(fleetCtrl.fleet(a, null, null, 1, 50).success()).isTrue();
    assertThat(fleetCtrl.zoneRiders(a, id).success()).isTrue();
    assertThat(
            fleetCtrl
                .forceStatus(a, id, new AdminFleetController.ForceStatusRequest("OFFLINE", "r"))
                .success())
        .isTrue();
    assertThat(fleetCtrl.forceStatus(a, id, null).success()).isTrue();
    assertThat(
            fleetCtrl.reassignZone(a, id, new AdminFleetController.ZoneRequest(id, true)).success())
        .isTrue();
    try {
      fleetCtrl.reassignZone(a, id, null);
    } catch (AppException ex) {
      assertThat(ex.code()).isEqualTo("INVALID_ZONE");
    }
    try {
      fleetCtrl.reassignZone(a, id, new AdminFleetController.ZoneRequest(null, true));
    } catch (AppException ex) {
      assertThat(ex.code()).isEqualTo("INVALID_ZONE");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void statusShiftJdbcAndCache() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(0L);

    Instant now = Instant.parse("2026-07-24T09:00:00Z");
    UUID riderId = Ids.newId();
    UUID zoneId = Ids.newId();
    JdbcRiderStore riders = new JdbcRiderStore(jdbc);
    riders.updateAvailability(riderId, "ONLINE", zoneId, now);
    riders.updatePrimaryZone(riderId, zoneId, now);

    JdbcRiderShiftStore shifts = new JdbcRiderShiftStore(jdbc);
    ShiftRecord shift =
        new ShiftRecord(Ids.newId(), riderId, zoneId, now, null, null, 0, 0L, null, now);
    shifts.insert(shift);
    shifts.insert(new ShiftRecord(Ids.newId(), riderId, zoneId, now, now, 5, 0, 0L, null, now));
    shifts.close(shift.id(), now, 10, null);
    assertThat(shifts.findOpenByRider(riderId)).isEmpty();
    assertThat(shifts.findLatestClosedByRider(riderId)).isEmpty();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
        .thenReturn(0);
    assertThat(shifts.sumDurationMinutesForRiderBetween(riderId, now, now.plusSeconds(3600)))
        .isZero();

    JdbcRiderStatusAuditStore audits = new JdbcRiderStatusAuditStore(jdbc);
    audits.insert(
        new AuditRecord(
            Ids.newId(), riderId, Ids.newId(), "admin_operations", "ONLINE", "OFFLINE", "r", now));
    assertThat(audits.findLatestForceChange(riderId)).isEmpty();

    JdbcRiderFleetStore fleet = new JdbcRiderFleetStore(jdbc);
    assertThat(
            fleet
                .listFleet(
                    new com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetFilter(
                        null, null, 1, 50))
                .total())
        .isZero();
    assertThat(
            fleet
                .listFleet(
                    new com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetFilter(
                        zoneId, null, 1, 50))
                .total())
        .isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    assertThat(
            fleet
                .listFleet(
                    new com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetFilter(
                        null, null, 1, 50))
                .total())
        .isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(0L);
    assertThat(fleet.countTripsToday(riderId, now, now.plusSeconds(86400))).isZero();
    assertThat(fleet.sumShiftEarningsTodayPaise(riderId, now, now.plusSeconds(86400))).isZero();
    assertThat(fleet.listByZone(zoneId)).isEmpty();
    assertThat(fleet.findFleetRow(riderId)).isEmpty();

    JdbcActiveDeliveryAdapter deliveries = new JdbcActiveDeliveryAdapter(jdbc);
    assertThat(deliveries.findActiveByRider(riderId)).isEmpty();
    assertThat(deliveries.countLiveOrdersInZone(zoneId)).isZero();
    deliveries.flagForMonitoring(Ids.newId(), "OFFLINE_DURING_DELIVERY");

    RedisRiderLiveStatusCache cache = new RedisRiderLiveStatusCache(null);
    cache.put(riderId, "ONLINE", Duration.ofMinutes(5));
    assertThat(cache.get(riderId)).contains("ONLINE");
    cache.evict(riderId);
    assertThat(cache.get(riderId)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getObject("rider_id")).thenReturn(riderId);
              when(rs.getObject("zone_id")).thenReturn(zoneId);
              when(rs.getTimestamp("shift_start")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("shift_end")).thenReturn(Timestamp.from(now));
              when(rs.getObject("duration_minutes")).thenReturn(10);
              when(rs.getInt("trips_in_shift")).thenReturn(0);
              when(rs.getLong("earnings_in_shift_paise")).thenReturn(0L);
              when(rs.getObject("force_closed_by")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getObject("changed_by")).thenReturn(Ids.newId());
              when(rs.getString("changed_by_role")).thenReturn("admin_operations");
              when(rs.getString("from_status")).thenReturn("ONLINE");
              when(rs.getString("to_status")).thenReturn("OFFLINE");
              when(rs.getString("reason")).thenReturn("r");
              when(rs.getString("name")).thenReturn("Ravi");
              when(rs.getString("phone")).thenReturn("+919876543210");
              when(rs.getObject("primary_zone_id")).thenReturn(zoneId);
              when(rs.getString("zone_name")).thenReturn("Koramangala");
              when(rs.getString("vehicle_type")).thenReturn("BIKE");
              when(rs.getString("status")).thenReturn("ONLINE");
              when(rs.getObject("current_zone_id")).thenReturn(zoneId);
              when(rs.getTimestamp("last_location_at")).thenReturn(Timestamp.from(now));
              when(rs.getObject("avg_rating")).thenReturn(null);
              when(rs.getObject("on_time_pct")).thenReturn(null);
              when(rs.getInt("daily_streak_days")).thenReturn(0);
              when(rs.getLong("earnings_wallet_balance_paise")).thenReturn(0L);
              when(rs.getString("area_locality")).thenReturn("HSR");
              when(rs.getObject("eta_minutes")).thenReturn(8);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(shifts.findOpenByRider(riderId)).isPresent();
    assertThat(audits.findLatestForceChange(riderId)).isPresent();
    assertThat(fleet.findFleetRow(riderId)).isPresent();
    assertThat(deliveries.findActiveByRider(riderId)).isPresent();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(deliveries.countLiveOrdersInZone(zoneId)).isZero();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getObject("rider_id")).thenReturn(riderId);
              when(rs.getObject("zone_id")).thenReturn(zoneId);
              when(rs.getTimestamp("shift_start")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("shift_end")).thenReturn(null);
              when(rs.getObject("duration_minutes")).thenReturn(null);
              when(rs.getInt("trips_in_shift")).thenReturn(0);
              when(rs.getLong("earnings_in_shift_paise")).thenReturn(0L);
              when(rs.getObject("force_closed_by")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getString("name")).thenReturn("Ravi");
              when(rs.getString("phone")).thenReturn("9876543210");
              when(rs.getObject("primary_zone_id")).thenReturn(null);
              when(rs.getString("zone_name")).thenReturn(null);
              when(rs.getString("vehicle_type")).thenReturn("BIKE");
              when(rs.getString("status")).thenReturn("OFFLINE");
              when(rs.getObject("current_zone_id")).thenReturn(null);
              when(rs.getTimestamp("last_location_at")).thenReturn(null);
              when(rs.getObject("avg_rating")).thenReturn(null);
              when(rs.getObject("on_time_pct")).thenReturn(null);
              when(rs.getInt("daily_streak_days")).thenReturn(0);
              when(rs.getLong("earnings_wallet_balance_paise")).thenReturn(0L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(shifts.findOpenByRider(riderId)).isPresent();
    assertThat(fleet.findFleetRow(riderId)).isPresent();
  }
}
