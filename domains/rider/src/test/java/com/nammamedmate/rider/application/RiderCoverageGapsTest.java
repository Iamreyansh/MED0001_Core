package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.adapter.in.web.AdminRiderController;
import com.nammamedmate.rider.adapter.out.client.StubAadhaarKycAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderKycDocumentStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderStore;
import com.nammamedmate.rider.adapter.out.storage.LocalRiderObjectStore;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore.DocumentRecord;
import com.nammamedmate.rider.application.port.out.RiderObjectStore;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RiderCoverageGapsTest {

  private static final UUID RIDER_ID = Ids.newId();
  private FakeRiders riders;
  private FakeDocs docs;
  private FakeObjects objects;
  private RiderKycService kyc;
  private AdminRiderService admin;
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);
  private final UUID adminId = Ids.newId();

  @BeforeEach
  void setUp() {
    riders = new FakeRiders();
    docs = new FakeDocs();
    objects = new FakeObjects();
    riders.insert(rider("PENDING_KYC", "NOT_SUBMITTED"));
    kyc =
        new RiderKycService(
            riders, docs, objects, new FakePresign(), new StubAadhaarKycAdapter(), clock, false);
    admin =
        new AdminRiderService(
            riders,
            docs,
            new FakePresign(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            (userId, at) -> 1,
            clock);
  }

  @Test
  void kycRemainingBranches() {
    MedmatePrincipal p = new MedmatePrincipal(RIDER_ID, AuthRole.RIDER, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> kyc.uploadDocument(p, "PAN", new byte[0], "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNSUPPORTED_FILE_FORMAT");
    assertThatThrownBy(
            () -> kyc.uploadDocument(p, "VEHICLE_INSURANCE", pdf(), "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> kyc.uploadDocument(p, "VEHICLE_INSURANCE", pdf(), "application/pdf", "bad", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> kyc.uploadDocument(p, "PAN", pdf(), null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNSUPPORTED_FILE_FORMAT");
    kyc.uploadDocument(p, "PAN", pdf(), "application/pdf; charset=utf-8", null, "  ");
    riders.update(rider("PENDING_KYC", "APPROVED"));
    assertThatThrownBy(() -> kyc.uploadDocument(p, "PAN", pdf(), "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("KYC_ALREADY_SUBMITTED");
    riders.update(rider("PENDING_KYC", "APPROVED"));
    assertThatThrownBy(() -> kyc.submitKyc(p))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("KYC_ALREADY_SUBMITTED");
    riders.update(rider("PENDING_KYC", "NOT_SUBMITTED"));
    docs.insert(
        new DocumentRecord(
            Ids.newId(),
            RIDER_ID,
            "DRIVING_LICENCE",
            null,
            "k",
            "u",
            1,
            "application/pdf",
            null,
            false,
            "PENDING",
            null,
            clock.instant(),
            null,
            null));
    docs.insert(
        new DocumentRecord(
            Ids.newId(),
            RIDER_ID,
            "PUC_CERTIFICATE",
            null,
            "k2",
            "u2",
            1,
            "application/pdf",
            LocalDate.parse("2026-07-20"),
            false,
            "PENDING",
            null,
            clock.instant(),
            null,
            null));
    assertThatThrownBy(() -> kyc.submitKyc(p))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCUMENT_EXPIRED_ON_SUBMIT");
    assertThatThrownBy(() -> RiderKycService.validateDocumentType(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DOCUMENT_TYPE");
    assertThatThrownBy(() -> RiderKycService.validateDocumentType("  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DOCUMENT_TYPE");
    assertThat(RiderKycService.sniffMime(new byte[] {0x25, 0x50, 0x44})).isNull();
    assertThat(RiderKycService.sniffMime(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x00})).isNull();
    assertThat(
            RiderKycService.sniffMime(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A}))
        .isNull();
    MedmatePrincipal customer =
        new MedmatePrincipal(RIDER_ID, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> kyc.submitKyc(customer))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    UUID missing = Ids.newId();
    MedmatePrincipal ghost =
        new MedmatePrincipal(missing, AuthRole.RIDER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> kyc.submitKyc(ghost))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_FOUND");
  }

  @Test
  void adminListBranchesAndNullBodies() {
    MedmatePrincipal a =
        new MedmatePrincipal(adminId, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    docs.insert(
        new DocumentRecord(
            Ids.newId(),
            RIDER_ID,
            "DRIVING_LICENCE",
            null,
            "key",
            "url",
            1,
            "application/pdf",
            null,
            false,
            "PENDING",
            null,
            clock.instant(),
            null,
            null));
    riders.update(rider("PENDING_KYC", "SUBMITTED"));
    var listed = admin.list(a, " ", "bogus", "DESC", 0, 0);
    assertThat(listed.meta().page()).isEqualTo(1);
    admin.list(a, null, "submitted_at", "asc", 1, 5);
    admin.list(a, "ALL", "name", "desc", 1, 200);
    admin.approve(a, RIDER_ID, null);
    riders.update(rider("PENDING_KYC", "SUBMITTED"));
    admin.reject(a, RIDER_ID, "X", null);
    riders.update(rider("ACTIVE", "APPROVED"));
    admin.block(a, RIDER_ID, "Y", null);
    admin.unblock(a, RIDER_ID, null);

    AdminRiderController ctrl = new AdminRiderController(admin);
    riders.update(rider("PENDING_KYC", "SUBMITTED"));
    assertThat(ctrl.approve(a, RIDER_ID, null).success()).isTrue();
    riders.update(rider("PENDING_KYC", "SUBMITTED"));
    assertThat(
            ctrl.reject(a, RIDER_ID, new AdminRiderController.RejectRequest("R", null)).success())
        .isTrue();
    // null body reject
    riders.update(rider("PENDING_KYC", "SUBMITTED"));
    assertThatThrownBy(() -> ctrl.reject(a, RIDER_ID, null)).isInstanceOf(AppException.class);
    riders.update(rider("ACTIVE", "APPROVED"));
    assertThatThrownBy(() -> ctrl.block(a, RIDER_ID, null)).isInstanceOf(AppException.class);
    riders.update(rider("BLOCKED", "APPROVED"));
    assertThat(ctrl.unblock(a, RIDER_ID, null).success()).isTrue();
  }

  @Test
  void registrationBlankEmailAndInactiveZone() {
    FakeZones zones = new FakeZones();
    UUID zone = Ids.newId();
    zones.map.put(zone, new ZoneLookupPort.ZoneInfo(zone, "Z", false));
    RiderRegistrationService reg = new RiderRegistrationService(riders, zones, clock);
    assertThatThrownBy(() -> reg.register("A", "9876543219", "  ", "BIKE", "KA01AB1234", zone))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ZONE");
    Map<String, Object> data = reg.register("A", "9876543218", null, "BICYCLE", "KA01AB9999", null);
    assertThat(data.get("status")).isEqualTo("PENDING_KYC");
    assertThatThrownBy(() -> reg.register("A", "9876543218", null, null, "KA01AB9999", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> reg.register("A".repeat(101), "9876543217", null, "BIKE", "KA01AB9998", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void localStoreDefaultCtorAndIoErrors(@TempDir Path tmp) throws Exception {
    LocalRiderObjectStore def = new LocalRiderObjectStore();
    def.put("x/y", new byte[] {1}, "application/pdf");
    def.delete("missing/key");

    Path fileAsBase = tmp.resolve("file-not-dir");
    Files.writeString(fileAsBase, "x");
    LocalRiderObjectStore bad = new LocalRiderObjectStore(fileAsBase);
    assertThatThrownBy(() -> bad.put("k", new byte[] {1}, "application/pdf"))
        .isInstanceOf(RuntimeException.class);

    Path base = tmp.resolve("store");
    Files.createDirectories(base);
    Files.createDirectories(base.resolve("dirkey"));
    Files.writeString(base.resolve("dirkey").resolve("child"), "x");
    LocalRiderObjectStore delFail = new LocalRiderObjectStore(base);
    assertThatThrownBy(() -> delFail.delete("dirkey")).isInstanceOf(RuntimeException.class);
  }

  @Test
  void finalBranchCleanup() {
    MedmatePrincipal a =
        new MedmatePrincipal(adminId, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    UUID zoneId = Ids.newId();
    Instant now = clock.instant();
    riders.update(
        new RiderRecord(
            RIDER_ID,
            "Ravi",
            "+919876543210",
            "e@x.com",
            "BIKE",
            "KA01AB1234",
            zoneId,
            "PENDING_KYC",
            "NOT_SUBMITTED",
            now,
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
            now));
    admin.list(a, "PENDING_KYC", null, null, null, null);
    admin.list(a, "PENDING_KYC", "  ", "nope", 0, 150);
    admin.list(a, "PENDING_KYC", "created_at", "DESC", 1, 20);
    assertThatThrownBy(() -> admin.reject(a, RIDER_ID, "  ", "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> admin.reject(a, RIDER_ID, "X", "n"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_KYC_STATE");
    assertThatThrownBy(() -> admin.block(a, RIDER_ID, "  ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> admin.list(null, null, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    MedmatePrincipal p = new MedmatePrincipal(RIDER_ID, AuthRole.RIDER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> kyc.uploadDocument(p, "PAN", null, "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNSUPPORTED_FILE_FORMAT");
    assertThatThrownBy(
            () -> kyc.uploadDocument(p, "PUC_CERTIFICATE", pdf(), "application/pdf", "   ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    // claimed pdf but garbage bytes → sniffed null
    assertThatThrownBy(
            () ->
                kyc.uploadDocument(
                    p, "PAN", new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNSUPPORTED_FILE_FORMAT");
    // jpeg length>=3 wrong middle byte
    assertThat(RiderKycService.sniffMime(new byte[] {(byte) 0xFF, 0x00, (byte) 0xFF})).isNull();
    // jpeg length < 3
    assertThat(RiderKycService.sniffMime(new byte[] {(byte) 0xFF, (byte) 0xD8})).isNull();
    // png length>=8 wrong content
    assertThat(
            RiderKycService.sniffMime(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x00}))
        .isNull();
    // sniffed non-null but mismatched claimed type
    assertThatThrownBy(() -> kyc.uploadDocument(p, "PAN", pdf(), "image/jpeg", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNSUPPORTED_FILE_FORMAT");
    docs.insert(
        new DocumentRecord(
            Ids.newId(),
            RIDER_ID,
            "DRIVING_LICENCE",
            null,
            "k",
            "u",
            1,
            "application/pdf",
            null,
            false,
            "PENDING",
            null,
            now,
            null,
            null));
    docs.insert(
        new DocumentRecord(
            Ids.newId(),
            RIDER_ID,
            "VEHICLE_INSURANCE",
            null,
            "k2",
            "u2",
            1,
            "application/pdf",
            null,
            false,
            "PENDING",
            null,
            now,
            null,
            null));
    kyc.submitKyc(p); // insurance with null expiry skips expired check

    RiderRegistrationService reg =
        new RiderRegistrationService(riders, id -> Optional.empty(), clock);
    assertThatThrownBy(() -> reg.register(null, "9876543210", null, "BIKE", "KA01AB1234", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    reg.register("Ok", "9876543299", "email@x.com", "BIKE", "KA01ZZ1234", null);
    reg.register("Ok2", "9876543298", "   ", "BIKE", "KA01ZZ1235", null);
  }

  @Test
  void aadhaarEnabledWithoutDocAndImageUploads() {
    RiderKycService withFlag =
        new RiderKycService(
            riders, docs, objects, new FakePresign(), new StubAadhaarKycAdapter(), clock, true);
    MedmatePrincipal p = new MedmatePrincipal(RIDER_ID, AuthRole.RIDER, null, TokenScope.FULL, "j");
    withFlag.uploadDocument(
        p,
        "DRIVING_LICENCE",
        new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0},
        "image/jpeg",
        null,
        null);
    withFlag.uploadDocument(
        p,
        "VEHICLE_RC",
        new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A},
        "image/png",
        null,
        null);
    withFlag.submitKyc(p);
    assertThat(riders.findById(RIDER_ID).orElseThrow().aadhaarVerified()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcNullBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null, 4);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null, 2);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              UUID id = Ids.newId();
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("rider_id")).thenReturn(id);
              when(rs.getString(anyString())).thenReturn("x");
              when(rs.getString("vehicle_type")).thenReturn("BIKE");
              when(rs.getString("status")).thenReturn("PENDING_KYC");
              when(rs.getString("kyc_status")).thenReturn("NOT_SUBMITTED");
              when(rs.getString("document_type")).thenReturn("PAN");
              when(rs.getString("mime_type")).thenReturn("application/pdf");
              when(rs.getString("verification_status")).thenReturn("PENDING");
              when(rs.getString("file_key")).thenReturn("k");
              when(rs.getString("file_url")).thenReturn("u");
              when(rs.getBoolean(anyString())).thenReturn(false);
              when(rs.getInt(anyString())).thenReturn(1);
              when(rs.getLong(anyString())).thenReturn(0L);
              when(rs.getObject("primary_zone_id")).thenReturn(null);
              when(rs.getObject("kyc_reviewed_by")).thenReturn(null);
              when(rs.getObject("blocked_by")).thenReturn(null);
              when(rs.getObject("reviewed_by")).thenReturn(null);
              when(rs.getObject("avg_rating")).thenReturn(null);
              when(rs.getObject("on_time_pct")).thenReturn(null);
              when(rs.getTimestamp(anyString())).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(clock.instant()));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(clock.instant()));
              when(rs.getTimestamp("uploaded_at")).thenReturn(Timestamp.from(clock.instant()));
              when(rs.getDate(anyString())).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    JdbcRiderStore store = new JdbcRiderStore(jdbc);
    assertThat(store.existsByPhone("x")).isFalse();
    assertThat(store.existsByPhone("y")).isTrue();
    assertThat(store.list(new ListFilter(null, "created_at", "asc", 1, 10)).total()).isZero();
    assertThat(store.findById(Ids.newId())).isPresent();
    JdbcRiderKycDocumentStore docsStore = new JdbcRiderKycDocumentStore(jdbc);
    assertThat(docsStore.countUploadsByRiderAndType(Ids.newId(), "PAN")).isZero();
    assertThat(docsStore.countUploadsByRiderAndType(Ids.newId(), "PAN")).isEqualTo(4);
    DocumentRecord mapped = docsStore.findActiveByRider(Ids.newId()).get(0);
    assertThat(mapped.expiryDate()).isNull();
    assertThat(mapped.reviewedAt()).isNull();
    docsStore.insert(
        new DocumentRecord(
            Ids.newId(),
            Ids.newId(),
            "PAN",
            null,
            "k",
            "u",
            1,
            "application/pdf",
            null,
            false,
            "PENDING",
            null,
            clock.instant(),
            null,
            null));
    docsStore.insert(
        new DocumentRecord(
            Ids.newId(),
            Ids.newId(),
            "PAN",
            null,
            "k",
            "u",
            1,
            "application/pdf",
            LocalDate.parse("2029-01-01"),
            false,
            "PENDING",
            null,
            clock.instant(),
            clock.instant(),
            Ids.newId()));
  }

  private RiderRecord rider(String status, String kycStatus) {
    Instant now = clock.instant();
    return new RiderRecord(
        RIDER_ID,
        "Ravi",
        "+919876543210",
        null,
        "BIKE",
        "KA01AB1234",
        null,
        status,
        kycStatus,
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
  }

  private static byte[] pdf() {
    return "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
  }

  static final class FakePresign implements PresignedUrlService {
    @Override
    public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
      return new PresignedUrl("u", key, ttl);
    }

    @Override
    public PresignedUrl createGetUrl(String key, Duration ttl) {
      return new PresignedUrl("https://x/" + key, key, ttl);
    }
  }

  static final class FakeObjects implements RiderObjectStore {
    @Override
    public void put(String key, byte[] bytes, String contentType) {}

    @Override
    public void delete(String key) {}
  }

  static final class FakeDocs implements RiderKycDocumentStore {
    final List<DocumentRecord> history = new CopyOnWriteArrayList<>();
    final List<UUID> deleted = new CopyOnWriteArrayList<>();

    @Override
    public void insert(DocumentRecord doc) {
      history.add(doc);
    }

    @Override
    public void softDelete(UUID id, Instant deletedAt) {
      deleted.add(id);
    }

    @Override
    public Optional<DocumentRecord> findActiveByRiderAndType(UUID riderId, String documentType) {
      return history.stream()
          .filter(d -> d.riderId().equals(riderId) && d.documentType().equals(documentType))
          .filter(d -> !deleted.contains(d.id()))
          .reduce((a, b) -> b);
    }

    @Override
    public List<DocumentRecord> findActiveByRider(UUID riderId) {
      return history.stream()
          .filter(d -> d.riderId().equals(riderId) && !deleted.contains(d.id()))
          .toList();
    }

    @Override
    public int countUploadsByRiderAndType(UUID riderId, String documentType) {
      return (int)
          history.stream()
              .filter(d -> d.riderId().equals(riderId) && d.documentType().equals(documentType))
              .count();
    }

    @Override
    public List<DocumentRecord> findDueForExpiryAlert(LocalDate onOrBefore, LocalDate after) {
      return List.of();
    }

    @Override
    public void markExpiryAlertSent(UUID documentId) {}
  }

  static final class FakeRiders implements RiderStore {
    final Map<UUID, RiderRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public Optional<RiderRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RiderRecord> findByPhone(String phone) {
      return byId.values().stream().filter(r -> r.phone().equals(phone)).findFirst();
    }

    @Override
    public boolean existsByPhone(String phone) {
      return findByPhone(phone).isPresent();
    }

    @Override
    public void update(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public PageResult list(ListFilter filter) {
      List<RiderRecord> rows = new ArrayList<>(byId.values());
      return new PageResult(rows, rows.size());
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {
      findById(id).ifPresent(r -> update(withStatus(r, status, updatedAt)));
    }

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {
      findById(id)
          .ifPresent(
              r ->
                  update(
                      new RiderRecord(
                          r.id(),
                          r.name(),
                          r.phone(),
                          r.email(),
                          r.vehicleType(),
                          r.vehiclePlateNumber(),
                          primaryZoneId,
                          r.status(),
                          r.kycStatus(),
                          r.kycSubmittedAt(),
                          r.kycReviewedAt(),
                          r.kycReviewedBy(),
                          r.kycRejectionReason(),
                          r.kycRejectionNotes(),
                          r.aadhaarVerified(),
                          r.avgRating(),
                          r.totalTrips(),
                          r.onTimePct(),
                          r.earningsWalletBalancePaise(),
                          r.codInHandPaise(),
                          r.dailyStreakDays(),
                          r.blockedReason(),
                          r.blockedBy(),
                          r.blockedAt(),
                          r.createdAt(),
                          updatedAt)));
    }

    private static RiderRecord withStatus(RiderRecord r, String status, Instant updatedAt) {
      return new RiderRecord(
          r.id(),
          r.name(),
          r.phone(),
          r.email(),
          r.vehicleType(),
          r.vehiclePlateNumber(),
          r.primaryZoneId(),
          status,
          r.kycStatus(),
          r.kycSubmittedAt(),
          r.kycReviewedAt(),
          r.kycReviewedBy(),
          r.kycRejectionReason(),
          r.kycRejectionNotes(),
          r.aadhaarVerified(),
          r.avgRating(),
          r.totalTrips(),
          r.onTimePct(),
          r.earningsWalletBalancePaise(),
          r.codInHandPaise(),
          r.dailyStreakDays(),
          r.blockedReason(),
          r.blockedBy(),
          r.blockedAt(),
          r.createdAt(),
          updatedAt);
    }
  }

  static final class FakeZones implements ZoneLookupPort {
    final Map<UUID, ZoneInfo> map = new HashMap<>();

    @Override
    public Optional<ZoneInfo> findById(UUID zoneId) {
      return Optional.ofNullable(map.get(zoneId));
    }
  }
}
