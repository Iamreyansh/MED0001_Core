package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.AdminRiderService.ListResult;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore.DocumentRecord;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminRiderServiceTest {

  private FakeRiderStore riders;
  private FakeDocs docs;
  private InMemoryOutboxStore outboxStore;
  private AdminRiderService service;
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T09:00:00Z"), ZoneOffset.UTC);
  private final UUID adminId = Ids.newId();
  private UUID riderId;

  @BeforeEach
  void setUp() {
    riders = new FakeRiderStore();
    docs = new FakeDocs();
    outboxStore = new InMemoryOutboxStore();
    service =
        new AdminRiderService(
            riders,
            docs,
            new FakePresign(),
            new OutboxPublisher(outboxStore, new ObjectMapper()),
            (userId, at) -> 1,
            clock);
    riderId = Ids.newId();
    riders.insert(rider("PENDING_KYC", "SUBMITTED"));
  }

  @Test
  void ac006_approveActivatesAndNotifies() {
    Map<String, Object> data = service.approve(admin(), riderId, "ok");
    assertThat(data.get("status")).isEqualTo("ACTIVE");
    assertThat(data.get("kyc_status")).isEqualTo("APPROVED");
    assertThat(riders.findById(riderId).orElseThrow().status()).isEqualTo("ACTIVE");
    assertThat(outboxStore.all()).anyMatch(m -> m.type().contains("kyc_approved"));
  }

  @Test
  void ac007_rejectSetsReason() {
    Map<String, Object> data =
        service.reject(admin(), riderId, "DOCUMENT_UNCLEAR", "blurry licence");
    assertThat(data.get("kyc_status")).isEqualTo("REJECTED");
    assertThat(data.get("rejection_reason")).isEqualTo("DOCUMENT_UNCLEAR");
    assertThat(data.get("rejection_notes")).isEqualTo("blurry licence");
    RiderRecord after = riders.findById(riderId).orElseThrow();
    assertThat(after.kycStatus()).isEqualTo("REJECTED");
    assertThat(after.kycRejectionReason()).isEqualTo("DOCUMENT_UNCLEAR");
    assertThat(after.kycRejectionNotes()).isEqualTo("blurry licence");
    assertThat(outboxStore.all()).anyMatch(m -> m.type().contains("kyc_rejected"));
  }

  @Test
  void ac008_blockActiveRider() {
    riders.update(rider("ACTIVE", "APPROVED"));
    Map<String, Object> data = service.block(admin(), riderId, "FRAUD_SUSPECTED", "notes");
    assertThat(data.get("status")).isEqualTo("BLOCKED");
    assertThat(riders.findById(riderId).orElseThrow().status()).isEqualTo("BLOCKED");
  }

  @Test
  void ac009_unblockRestoresActive() {
    riders.update(rider("BLOCKED", "APPROVED"));
    Map<String, Object> data = service.unblock(admin(), riderId, "cleared");
    assertThat(data.get("status")).isEqualTo("ACTIVE");
  }

  @Test
  void listAndErrors() {
    ListResult result = service.list(admin(), "PENDING_KYC", "created_at", "asc", 1, 20);
    assertThat(result.meta().total()).isEqualTo(1);
    assertThatThrownBy(() -> service.list(admin(), "NOPE", null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS_FILTER");
    assertThatThrownBy(() -> service.approve(admin(), Ids.newId(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_FOUND");
    riders.update(rider("PENDING_KYC", "NOT_SUBMITTED"));
    assertThatThrownBy(() -> service.approve(admin(), riderId, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_KYC_STATE");
    riders.update(rider("PENDING_KYC", "SUBMITTED"));
    assertThatThrownBy(() -> service.reject(admin(), riderId, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> service.block(admin(), riderId, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
    riders.update(rider("BLOCKED", "APPROVED"));
    assertThatThrownBy(() -> service.block(admin(), riderId, "X", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_ALREADY_BLOCKED");
    riders.update(rider("ACTIVE", "APPROVED"));
    assertThatThrownBy(() -> service.unblock(admin(), riderId, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_BLOCKED");
    assertThatThrownBy(
            () ->
                service.list(
                    new MedmatePrincipal(adminId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    null,
                    null,
                    null,
                    1,
                    20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  private MedmatePrincipal admin() {
    return new MedmatePrincipal(adminId, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private RiderRecord rider(String status, String kyc) {
    Instant now = clock.instant();
    return new RiderRecord(
        riderId,
        "Ravi",
        "+919876543210",
        "r@x.com",
        "BIKE",
        "KA01AB1234",
        null,
        status,
        kyc,
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
        now);
  }

  static final class FakePresign implements PresignedUrlService {
    @Override
    public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
      return new PresignedUrl("u", key, ttl);
    }

    @Override
    public PresignedUrl createGetUrl(String key, Duration ttl) {
      return new PresignedUrl("https://signed/" + key, key, ttl);
    }
  }

  static final class FakeDocs implements RiderKycDocumentStore {
    final List<DocumentRecord> docs = new CopyOnWriteArrayList<>();

    @Override
    public void insert(DocumentRecord doc) {
      docs.add(doc);
    }

    @Override
    public void softDelete(UUID id, Instant deletedAt) {}

    @Override
    public Optional<DocumentRecord> findActiveByRiderAndType(UUID riderId, String documentType) {
      return Optional.empty();
    }

    @Override
    public List<DocumentRecord> findActiveByRider(UUID riderId) {
      return docs.stream().filter(d -> d.riderId().equals(riderId)).toList();
    }

    @Override
    public int countUploadsByRiderAndType(UUID riderId, String documentType) {
      return 0;
    }

    @Override
    public List<DocumentRecord> findDueForExpiryAlert(LocalDate onOrBefore, LocalDate after) {
      return List.of();
    }

    @Override
    public void markExpiryAlertSent(UUID documentId) {}
  }

  static final class FakeRiderStore implements RiderStore {
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
      return Optional.empty();
    }

    @Override
    public boolean existsByPhone(String phone) {
      return false;
    }

    @Override
    public void update(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public PageResult list(ListFilter filter) {
      List<RiderRecord> rows =
          byId.values().stream()
              .filter(r -> filter.status() == null || filter.status().equals(r.status()))
              .toList();
      return new PageResult(new ArrayList<>(rows), rows.size());
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {
      update(
          findById(id)
              .map(
                  r ->
                      new RiderRecord(
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
                          updatedAt))
              .orElseThrow());
    }

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {
      update(
          findById(id)
              .map(
                  r ->
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
                          updatedAt))
              .orElseThrow());
    }
  }
}
