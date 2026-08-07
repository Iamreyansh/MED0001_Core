package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminListRow;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore.JobRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AdminBulkActionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:10:00Z");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private FakePharmacyStore pharmacies;
  private FakeBulkJobStore jobs;
  private AdminPharmacyActionsService actions;
  private AdminBulkActionService bulkService;
  private BulkActionJobProcessor processor;
  private AuditLogStore audit;

  @BeforeEach
  void setUp() {
    pharmacies = new FakePharmacyStore();
    jobs = new FakeBulkJobStore();
    audit = mock(AuditLogStore.class);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    OutboxPublisher outbox = new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper());

    actions =
        new AdminPharmacyActionsService(
            pharmacies,
            new AdminPharmacyActionsServiceTest.FakeNoticeStore(),
            new AdminPharmacyActionsServiceTest.FakeNoteStore(),
            new AdminPharmacyActionsServiceTest.FakeCallLogStore(),
            audit,
            new AdminPharmacyActionsServiceTest.FakeNotificationPort(),
            rateLimiter,
            clock,
            mock(ObjectProvider.class));

    bulkService =
        new AdminBulkActionService(
            jobs,
            pharmacies,
            actions,
            audit,
            outbox,
            mock(ObjectProvider.class),
            rateLimiter,
            clock);
    processor = new BulkActionJobProcessor(jobs, bulkService, actions, clock);
    pharmacies.putActive(PID);
  }

  @Test
  void submitBulkExportQueuesAndProcesses() {
    pharmacies.listByIdsResult = List.of(exportRow("PHM-1", "Biz", "Owner"));
    Map<String, Object> queued =
        bulkService.submitBulkAction(
            principal(AuthRole.ADMIN_OPERATIONS), List.of(PID), "EXPORT", Map.of());
    assertThat(queued.get("status")).isEqualTo("QUEUED");
    UUID jobId = UUID.fromString((String) queued.get("job_id"));
    processor.processJob(jobId);
    JobRow job = jobs.byId.get(jobId);
    assertThat(job.status()).isEqualTo("COMPLETED");
    assertThat(job.resultPayload().get("export_content").toString()).contains("PHM-1");
  }

  @Test
  void submitBulkSuspendBySuperAdmin() {
    MedmatePrincipal superAdmin = principal(AuthRole.ADMIN_SUPER);
    Map<String, Object> queued =
        bulkService.submitBulkAction(
            superAdmin,
            List.of(PID),
            "SUSPEND",
            Map.of("reason", "policy breach", "suspend_type", "TEMPORARY"));
    UUID jobId = UUID.fromString((String) queued.get("job_id"));
    processor.processJob(jobId);
    assertThat(pharmacies.details.get(PID).status()).isEqualTo("SUSPENDED");
  }

  @Test
  void getJobStatusAndNotFound() {
    UUID jobId = Ids.newId();
    jobs.byId.put(
        jobId,
        new JobRow(
            jobId,
            "EXPORT",
            Map.of(),
            List.of(PID),
            "COMPLETED",
            1,
            1,
            1,
            0,
            0,
            List.of(Map.of("pharmacy_id", PID.toString(), "reason", "NOTICE_RATE_LIMIT_EXCEEDED")),
            Map.of("download_url", "/export"),
            Ids.newId(),
            NOW,
            NOW,
            NOW));
    Map<String, Object> status =
        bulkService.getJobStatus(principal(AuthRole.ADMIN_OPERATIONS), jobId);
    assertThat(status.get("skipped_pharmacies")).isNotNull();
    assertThat(status.get("download_url")).isEqualTo("/export");

    assertThatThrownBy(
            () -> bulkService.getJobStatus(principal(AuthRole.ADMIN_OPERATIONS), Ids.newId()))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("JOB_NOT_FOUND"));
  }

  @Test
  void validationAndAuthBranches() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    assertThatThrownBy(() -> bulkService.submitBulkAction(null, List.of(PID), "EXPORT", Map.of()))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("UNAUTHORIZED"));
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    principal(AuthRole.ADMIN_SUPPORT), List.of(PID), "EXPORT", Map.of()))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> bulkService.submitBulkAction(ops, List.of(), "EXPORT", Map.of()))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PHARMACY_IDS_REQUIRED"));
    assertThatThrownBy(() -> bulkService.submitBulkAction(ops, List.of(PID), "BAD", Map.of()))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_ACTION"));
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    principal(AuthRole.ADMIN_SUPER),
                    List.of(PID),
                    "SUSPEND",
                    Map.of("reason", "r")))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    ops,
                    List.of(PID),
                    "SEND_NOTICE",
                    Map.of("channel", "WHATSAPP", "message", "m")))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    ops, List.of(PID), "SEND_NOTICE", Map.of("channel", "EMAIL", "message", "m")))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
  }

  @Test
  void suspendPharmacyNotFoundAndAlreadySuspended() {
    assertThatThrownBy(
            () ->
                bulkService.suspendPharmacy(
                    Ids.newId(),
                    Map.of("reason", "r", "suspend_type", "TEMPORARY"),
                    Ids.newId(),
                    "admin_super"))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PHARMACY_NOT_FOUND"));

    pharmacies.putActive(PID, "SUSPENDED");
    assertThatThrownBy(
            () ->
                bulkService.suspendPharmacy(
                    PID,
                    Map.of("reason", "r", "suspend_type", "TEMPORARY"),
                    Ids.newId(),
                    "admin_super"))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("ALREADY_SUSPENDED"));
  }

  @Test
  void exportCsvEscapesCommas() throws Exception {
    pharmacies.listByIdsResult = List.of(exportRow("PHM,1", "Biz \"Name\"", "Owner\nLine"));
    String csv = bulkService.exportPharmacies(List.of(PID));
    assertThat(csv).contains("\"PHM,1\"");
    assertThat(csv).contains("\"Biz \"\"Name\"\"\"");
  }

  @Test
  void processorPollAndSkipPaths() {
    UUID jobId = Ids.newId();
    jobs.byId.put(
        jobId,
        new JobRow(
            jobId,
            "SEND_NOTICE",
            Map.of(
                "channel", "WHATSAPP", "message", "m", "template_name", "PHARMACY_GENERAL_NOTICE"),
            List.of(PID, Ids.newId()),
            "QUEUED",
            2,
            0,
            0,
            0,
            0,
            List.of(),
            Map.of(),
            Ids.newId(),
            null,
            null,
            NOW));
    processor.pollQueuedJobs();
    JobRow completed = jobs.byId.get(jobId);
    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(completed.succeeded() + completed.skipped() + completed.failed()).isEqualTo(2);

    processor.processJob(jobId);
    assertThat(jobs.byId.get(jobId).status()).isEqualTo("COMPLETED");
  }

  @Test
  void processorExportFailure() {
    UUID jobId = Ids.newId();
    pharmacies.throwOnList = true;
    jobs.byId.put(
        jobId,
        new JobRow(
            jobId,
            "EXPORT",
            Map.of(),
            List.of(PID),
            "QUEUED",
            1,
            0,
            0,
            0,
            0,
            List.of(),
            Map.of(),
            Ids.newId(),
            null,
            null,
            NOW));
    processor.processJob(jobId);
    assertThat(jobs.byId.get(jobId).failed()).isEqualTo(1);
  }

  @Test
  void processorIgnoresMissingOrCompletedJobs() {
    processor.processJob(Ids.newId());
    UUID completed = Ids.newId();
    jobs.byId.put(
        completed,
        new JobRow(
            completed,
            "EXPORT",
            Map.of(),
            List.of(PID),
            "COMPLETED",
            1,
            1,
            1,
            0,
            0,
            List.of(),
            Map.of(),
            Ids.newId(),
            NOW,
            NOW,
            NOW));
    processor.processJob(completed);
    assertThat(jobs.byId.get(completed).status()).isEqualTo("COMPLETED");
  }

  @Test
  void processorFailedNonSkippable() {
    UUID jobId = Ids.newId();
    jobs.byId.put(
        jobId,
        new JobRow(
            jobId,
            "SEND_NOTICE",
            Map.of("channel", "WHATSAPP", "message", "m", "template_name", "BAD_TEMPLATE"),
            List.of(PID),
            "QUEUED",
            1,
            0,
            0,
            0,
            0,
            List.of(),
            Map.of(),
            principal(AuthRole.ADMIN_OPERATIONS).subject(),
            null,
            null,
            NOW));
    processor.processJob(jobId);
    assertThat(jobs.byId.get(jobId).failed()).isEqualTo(1);
  }

  @Test
  void toJobMapWithoutOptionalFields() {
    JobRow job =
        new JobRow(
            Ids.newId(),
            "EXPORT",
            Map.of(),
            List.of(PID),
            "QUEUED",
            1,
            0,
            0,
            0,
            0,
            List.of(),
            Map.of(),
            Ids.newId(),
            null,
            null,
            NOW);
    Map<String, Object> map = AdminBulkActionService.toJobMap(job);
    assertThat(map).doesNotContainKey("skipped_pharmacies");
    assertThat(map).doesNotContainKey("started_at");
  }

  @Test
  void rateLimitOnBulkSubmit() {
    RateLimiter limited = mock(RateLimiter.class);
    when(limited.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    AdminBulkActionService limitedService =
        new AdminBulkActionService(
            jobs,
            pharmacies,
            actions,
            audit,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            mock(ObjectProvider.class),
            limited,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(
            () ->
                limitedService.submitBulkAction(
                    principal(AuthRole.ADMIN_OPERATIONS), List.of(PID), "EXPORT", Map.of()))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("RATE_LIMIT_EXCEEDED"));
  }

  private static AdminListRow exportRow(String code, String business, String owner) {
    return new AdminListRow(
        PID,
        code,
        business,
        owner,
        "+91",
        "e@t.com",
        null,
        "Zone",
        "ACTIVE",
        "FREE",
        true,
        null,
        NOW,
        NOW,
        null,
        BigDecimal.ONE,
        1,
        1,
        100L,
        BigDecimal.TEN,
        BigDecimal.ONE,
        50L,
        NOW);
  }

  private static MedmatePrincipal principal(AuthRole role) {
    return new MedmatePrincipal(Ids.newId(), role, null, TokenScope.FULL, "jti");
  }

  static final class FakePharmacyStore implements AdminPharmacyStore {
    final Map<UUID, AdminDetailRow> details = new LinkedHashMap<>();
    List<AdminListRow> listByIdsResult = List.of();
    boolean throwOnList = false;
    boolean throwOnSuspend = false;

    void putActive(UUID id) {
      putActive(id, "ACTIVE");
    }

    void putActive(UUID id, String status) {
      details.put(
          id,
          new AdminDetailRow(
              id,
              "PHM",
              "Biz",
              "Owner",
              "+91",
              "e@t.com",
              "PHARMACY",
              Map.of(),
              null,
              null,
              null,
              null,
              status,
              "FREE",
              BigDecimal.valueOf(8),
              null,
              null,
              "ACTIVE".equals(status),
              true,
              null,
              NOW,
              NOW,
              null,
              null,
              null,
              null,
              null,
              null,
              null));
    }

    @Override
    public PageResult list(ListFilter filter) {
      return new PageResult(List.of(), 0);
    }

    @Override
    public List<AdminListRow> exportRows(ListFilter filter) {
      return List.of();
    }

    @Override
    public DirectorySummary directorySummary(Instant asOf) {
      return new DirectorySummary(0, 0, 0, 0, 0, 0, 0L, 0L, 0, 0L, asOf);
    }

    @Override
    public Optional<AdminDetailRow> findDetail(UUID pharmacyId) {
      return Optional.ofNullable(details.get(pharmacyId));
    }

    @Override
    public Map<String, String> documentStatusSummary(UUID pharmacyId) {
      return Map.of();
    }

    @Override
    public String nextCode() {
      return "PHM";
    }

    @Override
    public void approve(
        UUID pharmacyId,
        BigDecimal commissionPct,
        UUID zoneId,
        Instant activatedAt,
        Instant updatedAt) {}

    @Override
    public void reject(
        UUID pharmacyId,
        String rejectionReason,
        String rejectionDetails,
        boolean canReapply,
        Instant rejectedAt) {}

    @Override
    public void suspend(
        UUID pharmacyId, String suspendType, boolean canReapply, Instant suspendedAt) {
      if (throwOnSuspend) {
        throw new RuntimeException("boom");
      }
      putActive(pharmacyId, "SUSPENDED");
    }

    @Override
    public void reactivate(UUID pharmacyId, Instant reactivatedAt, boolean canReapply) {}

    @Override
    public void resetKycSla(UUID pharmacyId, Instant slaResetAt) {}

    @Override
    public List<UUID> listActivePharmacyIds() {
      return List.of();
    }

    @Override
    public List<AdminListRow> listByIds(List<UUID> pharmacyIds) {
      if (throwOnList) {
        throw new RuntimeException("db down");
      }
      return listByIdsResult;
    }

    @Override
    public void updateCommissionPct(UUID pharmacyId, BigDecimal commissionPct, Instant updatedAt) {}
  }

  static final class FakeBulkJobStore implements BulkActionJobStore {
    final Map<UUID, JobRow> byId = new LinkedHashMap<>();

    @Override
    public void insert(JobRow row) {
      byId.put(row.id(), row);
    }

    @Override
    public Optional<JobRow> findById(UUID jobId) {
      return Optional.ofNullable(byId.get(jobId));
    }

    @Override
    public List<JobRow> findQueued(int limit) {
      return byId.values().stream().filter(j -> "QUEUED".equals(j.status())).limit(limit).toList();
    }

    @Override
    public void markRunning(UUID jobId, Instant startedAt) {
      JobRow old = byId.get(jobId);
      byId.put(
          jobId,
          new JobRow(
              old.id(),
              old.action(),
              old.payload(),
              old.pharmacyIds(),
              "RUNNING",
              old.totalPharmacies(),
              old.processed(),
              old.succeeded(),
              old.failed(),
              old.skipped(),
              old.skippedPharmacies(),
              old.resultPayload(),
              old.initiatedBy(),
              startedAt,
              null,
              old.createdAt()));
    }

    @Override
    public void updateProgress(
        UUID jobId,
        int processed,
        int succeeded,
        int failed,
        int skipped,
        List<Map<String, Object>> skippedPharmacies) {}

    @Override
    public void markCompleted(
        UUID jobId,
        int processed,
        int succeeded,
        int failed,
        int skipped,
        List<Map<String, Object>> skippedPharmacies,
        Map<String, Object> resultPayload,
        Instant completedAt) {
      JobRow old = byId.get(jobId);
      byId.put(
          jobId,
          new JobRow(
              old.id(),
              old.action(),
              old.payload(),
              old.pharmacyIds(),
              "COMPLETED",
              old.totalPharmacies(),
              processed,
              succeeded,
              failed,
              skipped,
              skippedPharmacies,
              resultPayload,
              old.initiatedBy(),
              old.startedAt(),
              completedAt,
              old.createdAt()));
    }
  }
}
