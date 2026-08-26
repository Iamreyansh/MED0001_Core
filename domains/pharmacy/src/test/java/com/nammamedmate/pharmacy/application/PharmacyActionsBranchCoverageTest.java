package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.adapter.in.web.AdminBulkJobController;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcBulkActionJobStore;
import com.nammamedmate.pharmacy.application.AdminBulkActionServiceTest.FakeBulkJobStore;
import com.nammamedmate.pharmacy.application.AdminBulkActionServiceTest.FakePharmacyStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsServiceTest.FakeAuditStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsServiceTest.FakeCallLogStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsServiceTest.FakeNoteStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsServiceTest.FakeNoticeStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsServiceTest.FakeNotificationPort;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminListRow;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore.JobRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyNoticeStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyNoticeStore.NoticeRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PharmacyActionsBranchCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:10:00Z");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private FakePharmacyStore pharmacies;
  private FakeNoticeStore notices;
  private FakeBulkJobStore jobs;
  private AdminPharmacyActionsService actions;
  private AdminBulkActionService bulkService;
  private BulkActionJobProcessor processor;

  @BeforeEach
  void setUp() {
    pharmacies = new FakePharmacyStore();
    notices = new FakeNoticeStore();
    jobs = new FakeBulkJobStore();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);

    actions =
        new AdminPharmacyActionsService(
            pharmacies,
            notices,
            new FakeNoteStore(),
            new FakeCallLogStore(),
            new FakeAuditStore(),
            new FakeNotificationPort(),
            rateLimiter,
            clock,
            mock(ObjectProvider.class));

    bulkService =
        new AdminBulkActionService(
            jobs,
            pharmacies,
            actions,
            new FakeAuditStore(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            mock(ObjectProvider.class),
            rateLimiter,
            clock);
    processor = new BulkActionJobProcessor(jobs, bulkService, actions, clock);
    pharmacies.putActive(PID);
  }

  @Test
  void resolveChannelsInAppUrgentAndDisplayNames() {
    assertThat(AdminPharmacyActionsService.resolveChannels("IN_APP", "URGENT"))
        .containsExactly("IN_APP", "WHATSAPP", "EMAIL");
    assertThat(AdminPharmacyActionsService.adminDisplayName(AuthRole.ADMIN_OPERATIONS))
        .isEqualTo("Operations Manager");
    assertThat(AdminPharmacyActionsService.adminDisplayName(AuthRole.ADMIN_SUPPORT))
        .isEqualTo("Customer Support");
    assertThat(AdminPharmacyActionsService.adminDisplayName(AuthRole.ADMIN_COMPLIANCE))
        .isEqualTo("Compliance Officer");
  }

  @Test
  void noticeRateLimitWithNullOldestAndLongAuditMessage() {
    PharmacyNoticeStore staleCountStore =
        new PharmacyNoticeStore() {
          @Override
          public void insert(NoticeRow row) {}

          @Override
          public int countSince(UUID pharmacyId, Instant since) {
            return 3;
          }

          @Override
          public Instant oldestSentAtSince(UUID pharmacyId, Instant since) {
            return null;
          }
        };
    RateLimiter rl = mock(RateLimiter.class);
    when(rl.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    AdminPharmacyActionsService staleActions =
        new AdminPharmacyActionsService(
            pharmacies,
            staleCountStore,
            new FakeNoteStore(),
            new FakeCallLogStore(),
            new FakeAuditStore(),
            new FakeNotificationPort(),
            rl,
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(ObjectProvider.class));

    assertThatThrownBy(
            () ->
                staleActions.sendNotice(
                    principal(AuthRole.ADMIN_OPERATIONS), PID, "IN_APP", "s", "m", "NORMAL", null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> assertThat(((AppException) ex).code()).isEqualTo("NOTICE_RATE_LIMIT_EXCEEDED"));

    Map<String, Object> urgentAll =
        actions.sendNotice(
            principal(AuthRole.ADMIN_OPERATIONS),
            PID,
            "ALL",
            "Subject",
            "x".repeat(250),
            "URGENT",
            "PHARMACY_URGENT_ALERT");
    assertThat(urgentAll.get("channels_sent")).isEqualTo(List.of("IN_APP"));
  }

  @Test
  void redisIncrementSkipsExpireWhenNotFirst() {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.increment(any())).thenReturn(2L);
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redisTemplate);

    RateLimiter rl = mock(RateLimiter.class);
    when(rl.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    AdminPharmacyActionsService redisActions =
        new AdminPharmacyActionsService(
            pharmacies,
            notices,
            new FakeNoteStore(),
            new FakeCallLogStore(),
            new FakeAuditStore(),
            new FakeNotificationPort(),
            rl,
            Clock.fixed(NOW, ZoneOffset.UTC),
            provider);

    redisActions.sendNotice(
        principal(AuthRole.ADMIN_OPERATIONS), PID, "IN_APP", "Subject", "Body", "NORMAL", null);
    verify(redisTemplate, never()).expire(any(String.class), any(java.time.Duration.class));
  }

  @Test
  void bulkSuspendPermanentAndExportNullFields() throws Exception {
    bulkService.suspendPharmacy(
        PID, Map.of("reason", "fraud", "suspend_type", "PERMANENT"), Ids.newId(), "admin_super");

    pharmacies.listByIdsResult =
        List.of(
            new AdminListRow(
                PID, "plain", null, null, null, null, null, null, null, null, false, null, null,
                null, null, null, 0, 0, 0L, null, null, 0L, null));
    String csv = bulkService.exportPharmacies(List.of(PID));
    assertThat(csv).contains("plain,,");
  }

  @Test
  void bulkValidationPollRateLimitAndProcessorBranches() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    principal(AuthRole.ADMIN_SUPER),
                    List.of(PID),
                    "SUSPEND",
                    Map.of("reason", "r", "suspend_type", "INVALID")))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));

    RateLimiter limited = mock(RateLimiter.class);
    when(limited.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    when(limited.tryAcquire(
            eq("admin:bulk-jobs:get:" + ops.subject()), any(Integer.class), any(Integer.class)))
        .thenReturn(false);
    AdminBulkActionService pollLimited =
        new AdminBulkActionService(
            jobs,
            pharmacies,
            actions,
            new FakeAuditStore(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            mock(ObjectProvider.class),
            limited,
            Clock.fixed(NOW, ZoneOffset.UTC));
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
            List.of(),
            Map.of(),
            ops.subject(),
            NOW,
            NOW,
            NOW));
    assertThatThrownBy(() -> pollLimited.getJobStatus(ops, jobId))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("RATE_LIMIT_EXCEEDED"));

    UUID bulkMapJob = Ids.newId();
    jobs.byId.put(
        bulkMapJob,
        new JobRow(
            bulkMapJob,
            "BULK_MAP",
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
            ops.subject(),
            null,
            null,
            NOW));
    processor.processJob(bulkMapJob);
    assertThat(jobs.byId.get(bulkMapJob).status()).isEqualTo("QUEUED");

    UUID badActionJob = Ids.newId();
    jobs.byId.put(
        badActionJob,
        new JobRow(
            badActionJob,
            "UNKNOWN",
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
            ops.subject(),
            null,
            null,
            NOW));
    processor.processJob(badActionJob);
    assertThat(jobs.byId.get(badActionJob).failed()).isEqualTo(1);

    UUID missingPharmacyJob = Ids.newId();
    UUID missing = Ids.newId();
    jobs.byId.put(
        missingPharmacyJob,
        new JobRow(
            missingPharmacyJob,
            "SEND_NOTICE",
            Map.of(
                "channel", "WHATSAPP", "message", "m", "template_name", "PHARMACY_GENERAL_NOTICE"),
            List.of(missing),
            "QUEUED",
            1,
            0,
            0,
            0,
            0,
            List.of(),
            Map.of(),
            ops.subject(),
            null,
            null,
            NOW));
    processor.processJob(missingPharmacyJob);
    assertThat(jobs.byId.get(missingPharmacyJob).skipped()).isEqualTo(1);

    pharmacies.putActive(PID, "SUSPENDED");
    UUID suspendSkipJob = Ids.newId();
    jobs.byId.put(
        suspendSkipJob,
        new JobRow(
            suspendSkipJob,
            "SUSPEND",
            Map.of("reason", "r", "suspend_type", "TEMPORARY"),
            List.of(PID),
            "QUEUED",
            1,
            0,
            0,
            0,
            0,
            List.of(),
            Map.of(),
            ops.subject(),
            null,
            null,
            NOW));
    processor.processJob(suspendSkipJob);
    assertThat(jobs.byId.get(suspendSkipJob).skipped()).isEqualTo(1);
  }

  @Test
  void submitBulkActionTriggersProcessorWhenAvailable() {
    ObjectProvider<BulkActionJobProcessor> processorProvider = mock(ObjectProvider.class);
    doAnswer(
            inv -> {
              inv.getArgument(0, Consumer.class).accept(processor);
              return null;
            })
        .when(processorProvider)
        .ifAvailable(any());
    RateLimiter rl = mock(RateLimiter.class);
    when(rl.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    AdminBulkActionService wiredBulk =
        new AdminBulkActionService(
            jobs,
            pharmacies,
            actions,
            new FakeAuditStore(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            processorProvider,
            rl,
            Clock.fixed(NOW, ZoneOffset.UTC));
    pharmacies.listByIdsResult =
        List.of(
            new AdminListRow(
                PID,
                "PHM",
                "Biz",
                "Owner",
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
                NOW));
    Map<String, Object> result =
        wiredBulk.submitBulkAction(
            principal(AuthRole.ADMIN_OPERATIONS), List.of(PID), "EXPORT", Map.of());
    UUID jobId = UUID.fromString((String) result.get("job_id"));
    assertThat(jobs.byId.get(jobId).status()).isEqualTo("COMPLETED");
  }

  @Test
  void bulkJobControllerDownloadWithNullPayload() {
    AdminBulkActionService bulk = mock(AdminBulkActionService.class);
    BulkActionJobStore jobStore = mock(BulkActionJobStore.class);
    UUID jobId = Ids.newId();
    when(jobStore.findById(jobId))
        .thenReturn(
            Optional.of(
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
                    List.of(),
                    null,
                    Ids.newId(),
                    NOW,
                    NOW,
                    NOW)));
    AdminBulkJobController controller = new AdminBulkJobController(bulk, jobStore);
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThat(controller.downloadExport(principal, jobId).getBody()).isEqualTo("");
  }

  @Test
  void jdbcBulkActionJobStoreJsonAndUuidBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = mock(ObjectMapper.class);
    when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("bad") {});
    JdbcBulkActionJobStore brokenWrite = new JdbcBulkActionJobStore(jdbc, mapper);
    assertThatThrownBy(
            () ->
                brokenWrite.insert(
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
                        NOW)))
        .isInstanceOf(IllegalStateException.class);

    ObjectMapper realMapper = new ObjectMapper();
    JdbcBulkActionJobStore store = new JdbcBulkActionJobStore(jdbc, realMapper);
    UUID jobId = Ids.newId();
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(jobId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(jobId);
              when(rs.getString("action")).thenReturn("EXPORT");
              when(rs.getString("payload")).thenReturn("{bad");
              Array pharmacyIds = mock(Array.class);
              when(pharmacyIds.getArray()).thenReturn(new Object[] {PID.toString(), null});
              when(rs.getArray("pharmacy_ids")).thenReturn(pharmacyIds);
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getInt("total_pharmacies")).thenReturn(1);
              when(rs.getInt("processed")).thenReturn(0);
              when(rs.getInt("succeeded")).thenReturn(0);
              when(rs.getInt("failed")).thenReturn(0);
              when(rs.getInt("skipped")).thenReturn(0);
              when(rs.getString("skipped_pharmacies")).thenReturn("[bad");
              when(rs.getString("result_payload")).thenReturn(" ");
              when(rs.getObject("initiated_by")).thenReturn(Ids.newId());
              when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThatThrownBy(() -> store.findById(jobId)).isInstanceOf(IllegalStateException.class);

    UUID goodJob = Ids.newId();
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(goodJob)))
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(goodJob);
              when(rs.getString("action")).thenReturn("EXPORT");
              when(rs.getString("payload")).thenReturn("{\"ok\":true}");
              Array pharmacyIds = mock(Array.class);
              when(pharmacyIds.getArray()).thenReturn(new Object[] {PID.toString(), null});
              when(rs.getArray("pharmacy_ids")).thenReturn(pharmacyIds);
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getInt("total_pharmacies")).thenReturn(1);
              when(rs.getInt("processed")).thenReturn(0);
              when(rs.getInt("succeeded")).thenReturn(0);
              when(rs.getInt("failed")).thenReturn(0);
              when(rs.getInt("skipped")).thenReturn(0);
              when(rs.getString("skipped_pharmacies")).thenReturn("[]");
              when(rs.getString("result_payload")).thenReturn(null);
              when(rs.getObject("initiated_by")).thenReturn(Ids.newId());
              when(rs.getTimestamp("started_at")).thenReturn(null);
              when(rs.getTimestamp("completed_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(store.findById(goodJob).orElseThrow().pharmacyIds()).containsExactly(PID);
  }

  @Test
  void jdbcNoticeAndNoteEdgeBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var noticeStore =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyNoticeStore(jdbc);
    when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(PID), any(Timestamp.class)))
        .thenReturn(null);
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID), any(Timestamp.class)))
        .thenReturn(List.of());
    assertThat(noticeStore.countSince(PID, NOW)).isZero();
    assertThat(noticeStore.oldestSentAtSince(PID, NOW)).isNull();

    var noteStore = new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcAdminNoteStore(jdbc);
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID), eq(20), eq(0)))
        .thenReturn(List.of());
    when(jdbc.queryForObject(any(String.class), eq(Long.class), eq(PID))).thenReturn(null);
    assertThat(noteStore.list(PID, false, 20, 0)).isEmpty();
    assertThat(noteStore.count(PID, false)).isZero();

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(Ids.newId());
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    when(rs.getInt("duration_seconds")).thenReturn(30);
    when(rs.getString("call_outcome")).thenReturn("RESOLVED");
    when(rs.getString("notes")).thenReturn(null);
    when(rs.getObject("logged_by")).thenReturn(Ids.newId());
    when(rs.getTimestamp("logged_at")).thenReturn(null);
    assertThat(
            com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyCallLogStore.mapRow(rs, 0)
                .loggedAt())
        .isNull();
  }

  @Test
  void listNotesPaginationAndSupportNotice() {
    actions.sendNotice(
        principal(AuthRole.ADMIN_SUPPORT),
        PID,
        "IN_APP",
        "Subject",
        "Support message",
        "NORMAL",
        null);
    AdminPharmacyActionsService.NotesListResult paged =
        actions.listNotes(principal(AuthRole.ADMIN_FINANCE), PID, null, 0, 500);
    assertThat(paged.meta().page()).isEqualTo(1);
    assertThat(paged.meta().limit()).isEqualTo(100);
  }

  @Test
  void remainingActionServiceRoleAndValidationBranches() {
    assertThatThrownBy(
            () ->
                actions.sendNotice(
                    principal(AuthRole.ADMIN_SUPER),
                    PID,
                    " WHATSAPP ",
                    null,
                    "msg",
                    null,
                    "PHARMACY_GENERAL_NOTICE"))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("CHANNEL_UNAVAILABLE"));
    for (AuthRole role :
        List.of(
            AuthRole.ADMIN_SUPER,
            AuthRole.ADMIN_OPERATIONS,
            AuthRole.ADMIN_SUPPORT,
            AuthRole.ADMIN_COMPLIANCE,
            AuthRole.ADMIN_FINANCE)) {
      actions.addNote(principal(role), PID, "note", null);
      actions.listNotes(principal(role), PID, false, 2, 5);
    }
    actions.logCall(principal(AuthRole.ADMIN_SUPER), PID, 45, "NO_ANSWER", "  ");
    actions.sendNotice(
        principal(AuthRole.ADMIN_OPERATIONS),
        PID,
        "ALL",
        "Subject",
        "normal all",
        "NORMAL",
        "PHARMACY_GENERAL_NOTICE");
    assertThatThrownBy(
            () ->
                actions.logCall(principal(AuthRole.ADMIN_OPERATIONS), PID, null, "RESOLVED", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("DURATION_REQUIRED"));
  }

  @Test
  void redisIncrementNullSkipsExpire() {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.increment(any())).thenReturn(null);
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redisTemplate);
    RateLimiter rl = mock(RateLimiter.class);
    when(rl.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    AdminPharmacyActionsService redisActions =
        new AdminPharmacyActionsService(
            pharmacies,
            notices,
            new FakeNoteStore(),
            new FakeCallLogStore(),
            new FakeAuditStore(),
            new FakeNotificationPort(),
            rl,
            Clock.fixed(NOW, ZoneOffset.UTC),
            provider);
    redisActions.sendNotice(
        principal(AuthRole.ADMIN_OPERATIONS), PID, "IN_APP", "Subject", "Body", "NORMAL", null);
    verify(redisTemplate, never()).expire(any(String.class), any(java.time.Duration.class));
  }

  @Test
  void remainingBulkServiceValidationAndCsvBranches() throws Exception {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    assertThatThrownBy(() -> bulkService.submitBulkAction(ops, List.of(PID), null, Map.of()))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_ACTION"));
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    principal(AuthRole.ADMIN_SUPER),
                    List.of(PID),
                    "SUSPEND",
                    Map.of("reason", "  ", "suspend_type", "TEMPORARY")))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    ops,
                    List.of(PID),
                    "SEND_NOTICE",
                    Map.of("channel", "WHATSAPP", "message", "  ")))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    ops, List.of(PID), "SEND_NOTICE", Map.of("channel", "IN_APP", "message", "m")))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));

    pharmacies.listByIdsResult =
        List.of(
            new AdminListRow(
                PID,
                "simple",
                "Biz",
                "Owner",
                "+91",
                "e@t.com",
                null,
                "Zone",
                "ACTIVE",
                "FREE",
                true,
                NOW,
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
                NOW));
    assertThat(bulkService.exportPharmacies(List.of(PID))).contains("simple,Biz");
    Map<String, Object> fullJob =
        AdminBulkActionService.toJobMap(
            new JobRow(
                Ids.newId(),
                "EXPORT",
                Map.of(),
                List.of(PID),
                "COMPLETED",
                1,
                1,
                1,
                0,
                0,
                List.of(Map.of("pharmacy_id", PID.toString(), "reason", "SKIP")),
                Map.of("download_url", "/export"),
                Ids.newId(),
                NOW,
                NOW,
                NOW));
    assertThat(fullJob)
        .containsKeys("started_at", "completed_at", "skipped_pharmacies", "download_url");
  }

  @Test
  void processorRuntimeExceptionMarksFailed() {
    FakePharmacyStore throwingStore = new FakePharmacyStore();
    throwingStore.throwOnSuspend = true;
    throwingStore.putActive(PID);
    UUID jobId = Ids.newId();
    jobs.byId.put(
        jobId,
        new JobRow(
            jobId,
            "SUSPEND",
            Map.of("reason", "r", "suspend_type", "TEMPORARY"),
            List.of(PID),
            "QUEUED",
            1,
            0,
            0,
            0,
            0,
            List.of(),
            Map.of(),
            principal(AuthRole.ADMIN_SUPER).subject(),
            null,
            null,
            NOW));
    AdminBulkActionService suspendBulk =
        new AdminBulkActionService(
            jobs,
            throwingStore,
            actions,
            new FakeAuditStore(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            mock(ObjectProvider.class),
            mock(RateLimiter.class, inv -> true),
            Clock.fixed(NOW, ZoneOffset.UTC));
    BulkActionJobProcessor proc =
        new BulkActionJobProcessor(jobs, suspendBulk, actions, Clock.fixed(NOW, ZoneOffset.UTC));
    proc.processJob(jobId);
    assertThat(jobs.byId.get(jobId).failed()).isEqualTo(1);
  }

  private static MedmatePrincipal principal(AuthRole role) {
    return new MedmatePrincipal(Ids.newId(), role, null, TokenScope.FULL, "jti");
  }
}
