package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyActionsController;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyProfileController;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyRegistrationController;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyStorefrontController;
import com.nammamedmate.pharmacy.application.port.out.AdminNoteStore;
import com.nammamedmate.pharmacy.application.port.out.AdminNoteStore.NoteRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminListRow;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore.JobRow;
import com.nammamedmate.pharmacy.application.port.out.NotificationDispatchPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCallLogStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCallLogStore.CallLogRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyNoticeStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyNoticeStore.NoticeRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class AdminPharmacyActionsServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-24T00:10:00Z");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PID2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID PID3 = UUID.fromString("33333333-3333-4333-8333-333333333333");

  private FakePharmacyStore pharmacies;
  private FakeNoticeStore notices;
  private FakeNoteStore notes;
  private FakeCallLogStore callLogs;
  private FakeAuditStore audit;
  private FakeNotificationPort notificationPort;
  private AdminPharmacyActionsService actions;
  private FakeBulkJobStore jobs;
  private AdminBulkActionService bulkService;
  private BulkActionJobProcessor processor;

  @BeforeEach
  void setUp() {
    pharmacies = new FakePharmacyStore();
    notices = new FakeNoticeStore();
    notes = new FakeNoteStore();
    callLogs = new FakeCallLogStore();
    audit = new FakeAuditStore();
    notificationPort = new FakeNotificationPort();
    jobs = new FakeBulkJobStore();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    OutboxPublisher outbox = new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper());

    ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisProvider =
        mock(ObjectProvider.class);
    actions =
        new AdminPharmacyActionsService(
            pharmacies,
            notices,
            notes,
            callLogs,
            audit,
            notificationPort,
            rateLimiter,
            clock,
            redisProvider);

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
    pharmacies.putActive(PID2, "SUSPENDED");
  }

  /** AC1: WA notice creates PharmacyNotice, audit NOTICE_SENT, decrements rate_limit_remaining. */
  @Test
  void ac1_whatsappNoticeCreatesRecordAuditAndRateLimit() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    Map<String, Object> result =
        actions.sendNotice(
            ops, PID, "WHATSAPP", null, "Renewal reminder", "NORMAL", "PHARMACY_GENERAL_NOTICE");

    assertThat(result.get("notice_id")).isNotNull();
    assertThat(result.get("channels_sent")).isEqualTo(List.of("WHATSAPP"));
    assertThat(result.get("rate_limit_remaining")).isEqualTo(2);
    assertThat(notices.inserted).hasSize(1);
    assertThat(notificationPort.lastTemplate).isEqualTo("PHARMACY_GENERAL_NOTICE");
    assertThat(audit.entries).hasSize(1);
    assertThat(audit.entries.getFirst().action()).isEqualTo("NOTICE_SENT");
  }

  /** AC2: 4th notice in rolling hour returns 429 NOTICE_RATE_LIMIT_EXCEEDED. */
  @Test
  void ac2_fourthNoticeReturnsRateLimitExceeded() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    for (int i = 0; i < 3; i++) {
      notices.inserted.add(sampleNotice(PID, NOW.minusSeconds(600L * i)));
    }

    assertThatThrownBy(
            () -> actions.sendNotice(ops, PID, "EMAIL", "Subject", "Body", "NORMAL", null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("NOTICE_RATE_LIMIT_EXCEEDED");
              assertThat(app.httpStatus()).isEqualTo(429);
              assertThat(app.details()).containsKey("rate_limit_reset_at");
            });
  }

  /** AC3: Flagged note stored and visible via GET notes; not on pharmacy APIs. */
  @Test
  void ac3_flaggedNoteAdminOnly() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    Map<String, Object> created = actions.addNote(ops, PID, "COMPLIANCE CONCERN", true);
    assertThat(created.get("is_flagged")).isEqualTo(true);

    AdminPharmacyActionsService.NotesListResult listed = actions.listNotes(ops, PID, true, 1, 20);
    assertThat(listed.data().get("notes")).asList().hasSize(1);

    assertThat(actions.listNotes(principal(AuthRole.ADMIN_FINANCE), PID, null, 1, 20).data())
        .containsKey("notes");
    assertThat(actions.listNotes(principal(AuthRole.ADMIN_COMPLIANCE), PID, null, 1, 20).data())
        .containsKey("notes");

    assertPharmacyControllersHaveNoNotesEndpoint();
  }

  /** AC4: Call log created immutable (no edit/delete endpoints). */
  @Test
  void ac4_callLogImmutable() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    Map<String, Object> result =
        actions.logCall(ops, PID, 342, "FOLLOW_UP_REQUIRED", "Follow up in 3 days");
    assertThat(result.get("call_log_id")).isNotNull();
    assertThat(result.get("duration_formatted")).isEqualTo("5m 42s");
    assertThat(callLogs.inserted).hasSize(1);
    assertAdminControllerHasNoCallLogMutations();
  }

  /** AC5: Bulk SUSPEND by admin_operations returns 403 FORBIDDEN_SUSPEND. */
  @Test
  void ac5_bulkSuspendForbiddenForOperations() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    ops,
                    List.of(PID),
                    "SUSPEND",
                    Map.of("reason", "policy", "suspend_type", "TEMPORARY")))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN_SUSPEND"));
  }

  /** AC6: 101 pharmacy IDs returns 400 TOO_MANY_PHARMACIES. */
  @Test
  void ac6_tooManyPharmaciesRejected() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      ids.add(Ids.newId());
    }
    assertThatThrownBy(() -> bulkService.submitBulkAction(ops, ids, "EXPORT", Map.of()))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("TOO_MANY_PHARMACIES"));
  }

  /** AC7: Bulk SEND_NOTICE skips rate-limited pharmacies; job shows succeeded/skipped. */
  @Test
  void ac7_bulkSendNoticeSkipsRateLimited() {
    pharmacies.putActive(PID3);
    for (int i = 0; i < 3; i++) {
      notices.inserted.add(sampleNotice(PID2, NOW.minusSeconds(100L * i)));
      notices.inserted.add(sampleNotice(PID3, NOW.minusSeconds(100L * i)));
    }

    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    Map<String, Object> submit =
        bulkService.submitBulkAction(
            ops,
            List.of(PID, PID2, PID3),
            "SEND_NOTICE",
            Map.of(
                "channel",
                "WHATSAPP",
                "message",
                "Bulk notice",
                "template_name",
                "PHARMACY_GENERAL_NOTICE"));

    assertThat(submit.get("job_id")).isNotNull();
    assertThat(submit.get("status")).isEqualTo("QUEUED");

    UUID jobId = UUID.fromString((String) submit.get("job_id"));
    processor.processJob(jobId);

    Map<String, Object> status = bulkService.getJobStatus(ops, jobId);
    assertThat(status.get("status")).isEqualTo("COMPLETED");
    assertThat(status.get("succeeded")).isEqualTo(1);
    assertThat(status.get("skipped")).isEqualTo(2);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> skipped =
        (List<Map<String, Object>>) status.get("skipped_pharmacies");
    assertThat(skipped).hasSize(2);
    assertThat(skipped)
        .allSatisfy(
            entry -> assertThat(entry.get("reason")).isEqualTo("NOTICE_RATE_LIMIT_EXCEEDED"));
  }

  /** AC8: Invalid WhatsApp template returns 400 INVALID_TEMPLATE without dispatch. */
  @Test
  void ac8_invalidTemplateRejected() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    assertThatThrownBy(
            () ->
                actions.sendNotice(ops, PID, "WHATSAPP", null, "msg", "NORMAL", "UNKNOWN_TEMPLATE"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_TEMPLATE"));
    assertThat(notices.inserted).isEmpty();
    assertThat(notificationPort.dispatchCount).isZero();
  }

  @Test
  void resolveChannelsBranches() {
    assertThat(AdminPharmacyActionsService.resolveChannels("ALL", "NORMAL"))
        .containsExactly("IN_APP", "WHATSAPP");
    assertThat(AdminPharmacyActionsService.resolveChannels("ALL", "URGENT"))
        .containsExactly("WHATSAPP", "EMAIL", "IN_APP");
    assertThat(AdminPharmacyActionsService.resolveChannels("WHATSAPP", "URGENT"))
        .containsExactly("WHATSAPP", "EMAIL");
  }

  @Test
  void complianceEmailOnly() {
    MedmatePrincipal compliance = principal(AuthRole.ADMIN_COMPLIANCE);
    assertThatThrownBy(
            () ->
                actions.sendNotice(
                    compliance, PID, "WHATSAPP", null, "msg", "NORMAL", "PHARMACY_GENERAL_NOTICE"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void supportCannotSendUrgent() {
    MedmatePrincipal support = principal(AuthRole.ADMIN_SUPPORT);
    assertThatThrownBy(
            () ->
                actions.sendNotice(
                    support, PID, "WHATSAPP", null, "msg", "URGENT", "PHARMACY_URGENT_ALERT"))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void noteRequiredValidation() {
    assertThatThrownBy(
            () -> actions.addNote(principal(AuthRole.ADMIN_OPERATIONS), PID, "  ", false))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("NOTE_REQUIRED"));
  }

  @Test
  void callLogValidationBranches() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    assertThatThrownBy(() -> actions.logCall(ops, PID, 0, "RESOLVED", null))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("DURATION_REQUIRED"));
    assertThatThrownBy(() -> actions.logCall(ops, PID, 10, "BAD", null))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_CALL_OUTCOME"));
  }

  @Test
  void pharmacyNotFoundAndValidationBranches() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    UUID missing = Ids.newId();
    assertThatThrownBy(
            () ->
                actions.sendNotice(
                    ops, missing, "WHATSAPP", null, "m", "NORMAL", "PHARMACY_GENERAL_NOTICE"))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PHARMACY_NOT_FOUND"));

    assertThatThrownBy(() -> actions.sendNotice(ops, PID, "BAD", null, "m", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_CHANNEL"));
    assertThatThrownBy(() -> actions.sendNotice(ops, PID, "WHATSAPP", null, "m", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("TEMPLATE_REQUIRED"));
    assertThatThrownBy(() -> actions.sendNotice(ops, PID, "IN_APP", null, "m", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("SUBJECT_REQUIRED"));
    assertThatThrownBy(() -> actions.addNote(ops, missing, "n", false))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PHARMACY_NOT_FOUND"));
    assertThatThrownBy(() -> actions.logCall(ops, missing, 10, "RESOLVED", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PHARMACY_NOT_FOUND"));
  }

  @Test
  void adminDisplayNamesAndUnauthorized() {
    assertThat(AdminPharmacyActionsService.adminDisplayName(AuthRole.ADMIN_SUPER))
        .isEqualTo("Super Administrator");
    assertThat(AdminPharmacyActionsService.adminDisplayName(AuthRole.ADMIN_FINANCE))
        .isEqualTo("Finance Manager");
    assertThat(AdminPharmacyActionsService.adminDisplayName(AuthRole.CUSTOMER)).isEqualTo("Admin");

    assertThatThrownBy(() -> actions.sendNotice(null, PID, "EMAIL", "s", "m", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("UNAUTHORIZED"));
    assertThatThrownBy(() -> actions.addNote(null, PID, "n", false))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("UNAUTHORIZED"));
    assertThatThrownBy(() -> actions.logCall(null, PID, 10, "RESOLVED", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("UNAUTHORIZED"));
    assertThatThrownBy(
            () ->
                actions.sendNotice(
                    principal(AuthRole.ADMIN_FINANCE),
                    PID,
                    "WHATSAPP",
                    null,
                    "m",
                    "NORMAL",
                    "PHARMACY_GENERAL_NOTICE"))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void listNotesWithoutFlagFilter() {
    AdminPharmacyActionsService.NotesListResult result =
        actions.listNotes(principal(AuthRole.ADMIN_FINANCE), PID, null, 1, 20);
    assertThat(result.data().get("notes")).asList().hasSize(1);
  }

  @Test
  void emailNoticeWithComplianceRole() {
    Map<String, Object> result =
        actions.sendNotice(
            principal(AuthRole.ADMIN_COMPLIANCE),
            PID,
            "EMAIL",
            "Compliance notice",
            "Please renew FSSAI",
            "NORMAL",
            null);
    assertThat(result.get("channels_sent")).isEqualTo(List.of("EMAIL"));
  }

  @Test
  void rateLimitOnNotice() {
    RateLimiter limited = mock(RateLimiter.class);
    when(limited.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    AdminPharmacyActionsService limitedActions =
        new AdminPharmacyActionsService(
            pharmacies,
            notices,
            notes,
            callLogs,
            audit,
            notificationPort,
            limited,
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(ObjectProvider.class));
    assertThatThrownBy(
            () ->
                limitedActions.sendNotice(
                    principal(AuthRole.ADMIN_OPERATIONS), PID, "EMAIL", "s", "m", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("RATE_LIMIT_EXCEEDED"));
  }

  @Test
  void messageAndSubjectLengthValidation() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    assertThatThrownBy(() -> actions.sendNotice(ops, PID, "EMAIL", "s", null, "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
    assertThatThrownBy(
            () -> actions.sendNotice(ops, PID, "EMAIL", "s", "x".repeat(2001), "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
    assertThatThrownBy(
            () -> actions.sendNotice(ops, PID, "IN_APP", "x".repeat(201), "body", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
  }

  @Test
  void invalidPriorityAndTemplateRequiredBranches() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    assertThatThrownBy(
            () ->
                actions.sendNotice(
                    ops, PID, "WHATSAPP", null, "m", "BAD", "PHARMACY_GENERAL_NOTICE"))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
    assertThatThrownBy(() -> actions.sendNotice(ops, PID, "IN_APP", null, "m", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("SUBJECT_REQUIRED"));
  }

  @Test
  void internalSkipWhenPharmacyNotActive() {
    RateLimiter limited = mock(RateLimiter.class);
    when(limited.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    AdminPharmacyActionsService bulkActions =
        new AdminPharmacyActionsService(
            pharmacies,
            notices,
            notes,
            callLogs,
            audit,
            notificationPort,
            limited,
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(ObjectProvider.class));

    AdminPharmacyActionsService.NoticeResult result =
        bulkActions.sendNoticeInternal(
            principal(AuthRole.ADMIN_OPERATIONS),
            PID2,
            "WHATSAPP",
            null,
            "msg",
            "NORMAL",
            "PHARMACY_GENERAL_NOTICE",
            Ids.newId(),
            false);
    assertThat(result.skipReason()).isEqualTo("PHARMACY_NOT_ACTIVE");
  }

  @Test
  void redisNoticeRateCounter() {
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
        mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
        mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.increment(any())).thenReturn(1L);
    ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> provider =
        mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redisTemplate);

    RateLimiter redisRateLimiter = mock(RateLimiter.class);
    when(redisRateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(true);
    AdminPharmacyActionsService redisActions =
        new AdminPharmacyActionsService(
            pharmacies,
            notices,
            notes,
            callLogs,
            audit,
            notificationPort,
            redisRateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC),
            provider);
    redisActions.sendNotice(
        principal(AuthRole.ADMIN_OPERATIONS), PID, "EMAIL", "Subject", "Body", "NORMAL", null);
    verify(redisTemplate).expire(any(String.class), any(java.time.Duration.class));
  }

  @Test
  void roleGuardsForNotesAndCalls() {
    assertThatThrownBy(() -> actions.addNote(principal(AuthRole.CUSTOMER), PID, "n", false))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(
            () -> actions.logCall(principal(AuthRole.ADMIN_FINANCE), PID, 10, "RESOLVED", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(
            () ->
                actions.addNote(principal(AuthRole.ADMIN_OPERATIONS), PID, "x".repeat(2001), false))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
    assertThatThrownBy(
            () ->
                actions.logCall(
                    principal(AuthRole.ADMIN_OPERATIONS), PID, 10, "RESOLVED", "x".repeat(1001)))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
  }

  @Test
  void bulkExportProducesCsv() throws Exception {
    pharmacies.listByIdsResult =
        List.of(
            new AdminListRow(
                PID,
                "PHM-1",
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
                BigDecimal.valueOf(4.5),
                10,
                5,
                10000L,
                BigDecimal.TEN,
                BigDecimal.valueOf(8),
                500L,
                NOW));

    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    UUID jobId = Ids.newId();
    jobs.insert(
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
            ops.subject(),
            null,
            null,
            NOW));
    processor.processJob(jobId);
    JobRow job = jobs.byId.get(jobId);
    assertThat(job.resultPayload().get("export_content").toString()).contains("code,business_name");
  }

  private static MedmatePrincipal principal(AuthRole role) {
    return new MedmatePrincipal(Ids.newId(), role, null, TokenScope.FULL, "jti");
  }

  private static NoticeRow sampleNotice(UUID pharmacyId, Instant sentAt) {
    return new NoticeRow(
        Ids.newId(),
        pharmacyId,
        List.of("EMAIL"),
        "s",
        "m",
        null,
        "NORMAL",
        Ids.newId(),
        sentAt,
        null);
  }

  private static void assertPharmacyControllersHaveNoNotesEndpoint() {
    List<Class<?>> controllers =
        List.of(
            PharmacyStorefrontController.class,
            PharmacyProfileController.class,
            PharmacyRegistrationController.class);
    for (Class<?> controller : controllers) {
      assertThat(hasNotesMapping(controller))
          .as("%s must not expose /notes", controller.getSimpleName())
          .isFalse();
    }
  }

  private static void assertAdminControllerHasNoCallLogMutations() {
    for (Method method : AdminPharmacyActionsController.class.getDeclaredMethods()) {
      if (!hasMutationMapping(method)) {
        continue;
      }
      assertThat(mappingPaths(method))
          .as("%s must not mutate call-log", method.getName())
          .noneMatch(path -> path.contains("call-log"));
    }
  }

  private static boolean hasNotesMapping(Class<?> controller) {
    for (Method method : controller.getDeclaredMethods()) {
      if (methodMapsToNotes(method)) {
        return true;
      }
    }
    return false;
  }

  private static boolean methodMapsToNotes(Method method) {
    return mappingPaths(method).stream().anyMatch(path -> path.contains("notes"));
  }

  private static boolean hasMutationMapping(Method method) {
    return method.isAnnotationPresent(PatchMapping.class)
        || method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class);
  }

  private static List<String> mappingPaths(Method method) {
    List<String> paths = new ArrayList<>();
    for (Annotation annotation : method.getAnnotations()) {
      String path = mappingValue(annotation);
      if (path != null) {
        paths.add(path);
      }
    }
    return paths;
  }

  private static String mappingValue(Annotation annotation) {
    Class<? extends Annotation> type = annotation.annotationType();
    if (type.equals(GetMapping.class)
        || type.equals(PostMapping.class)
        || type.equals(PatchMapping.class)
        || type.equals(PutMapping.class)
        || type.equals(DeleteMapping.class)
        || type.equals(RequestMapping.class)) {
      try {
        String[] values = (String[]) type.getMethod("value").invoke(annotation);
        if (values.length > 0) {
          return values[0];
        }
        String[] paths = (String[]) type.getMethod("path").invoke(annotation);
        if (paths.length > 0) {
          return paths[0];
        }
      } catch (ReflectiveOperationException ex) {
        throw new AssertionError("Failed to read mapping annotation", ex);
      }
    }
    return null;
  }

  static final class FakePharmacyStore implements AdminPharmacyStore {
    final Map<UUID, AdminDetailRow> details = new LinkedHashMap<>();
    List<AdminListRow> listByIdsResult = List.of();

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
    public com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.PageResult list(
        ListFilter filter) {
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
      return "PHM-1";
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
      return listByIdsResult;
    }

    @Override
    public void updateCommissionPct(UUID pharmacyId, BigDecimal commissionPct, Instant updatedAt) {}
  }

  static final class FakeNoticeStore implements PharmacyNoticeStore {
    final List<NoticeRow> inserted = new ArrayList<>();

    @Override
    public void insert(NoticeRow row) {
      inserted.add(row);
    }

    @Override
    public int countSince(UUID pharmacyId, Instant since) {
      return (int)
          inserted.stream()
              .filter(n -> n.pharmacyId().equals(pharmacyId) && !n.sentAt().isBefore(since))
              .count();
    }

    @Override
    public Instant oldestSentAtSince(UUID pharmacyId, Instant since) {
      return inserted.stream()
          .filter(n -> n.pharmacyId().equals(pharmacyId) && !n.sentAt().isBefore(since))
          .map(NoticeRow::sentAt)
          .min(Instant::compareTo)
          .orElse(null);
    }
  }

  static final class FakeNoteStore implements AdminNoteStore {
    @Override
    public void insert(NoteRow row) {}

    @Override
    public List<NoteRow> list(UUID pharmacyId, Boolean flaggedOnly, int limit, int offset) {
      return List.of(new NoteRow(Ids.newId(), pharmacyId, "flagged", true, Ids.newId(), NOW));
    }

    @Override
    public long count(UUID pharmacyId, Boolean flaggedOnly) {
      return flaggedOnly != null && flaggedOnly ? 1 : 1;
    }
  }

  static final class FakeCallLogStore implements PharmacyCallLogStore {
    final List<CallLogRow> inserted = new ArrayList<>();

    @Override
    public void insert(CallLogRow row) {
      inserted.add(row);
    }
  }

  static final class FakeAuditStore implements AuditLogStore {
    final List<AuditLogRecord> entries = new ArrayList<>();

    @Override
    public void append(AuditLogRecord record) {
      entries.add(record);
    }
  }

  static final class FakeNotificationPort implements NotificationDispatchPort {
    String lastTemplate;
    int dispatchCount = 0;

    @Override
    public void dispatchPerformanceAlert(
        UUID pharmacyId, String alertType, String message, List<String> channels) {}

    @Override
    public void dispatchSettlementReleased(UUID pharmacyId, UUID settlementId, long netPaidPaise) {}

    @Override
    public void dispatchSettlementPaid(
        UUID pharmacyId, UUID settlementId, long netPaidPaise, String utrNumber) {}

    @Override
    public void dispatchSettlementHeld(UUID pharmacyId, UUID settlementId, String reason) {}

    @Override
    public void dispatchPharmacyNotice(
        UUID pharmacyId,
        List<String> channels,
        String templateName,
        String subject,
        String message,
        String priority) {
      dispatchCount++;
      lastTemplate = templateName;
    }
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
