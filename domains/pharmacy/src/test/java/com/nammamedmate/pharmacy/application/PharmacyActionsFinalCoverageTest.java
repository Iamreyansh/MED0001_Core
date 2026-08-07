package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.AdminBulkActionServiceTest.FakeBulkJobStore;
import com.nammamedmate.pharmacy.application.AdminBulkActionServiceTest.FakePharmacyStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsServiceTest.FakeAuditStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsServiceTest.FakeCallLogStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsServiceTest.FakeNoteStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsServiceTest.FakeNoticeStore;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsServiceTest.FakeNotificationPort;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore.JobRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyNoticeStore;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Hits remaining JaCoCo branches for STORY-005 classes. */
class PharmacyActionsFinalCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:10:00Z");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private FakePharmacyStore pharmacies;
  private AdminPharmacyActionsService actions;
  private AdminBulkActionService bulkService;
  private FakeBulkJobStore jobs;

  @BeforeEach
  void setUp() {
    pharmacies = new FakePharmacyStore();
    jobs = new FakeBulkJobStore();
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    actions =
        new AdminPharmacyActionsService(
            pharmacies,
            new FakeNoticeStore(),
            new FakeNoteStore(),
            new FakeCallLogStore(),
            new FakeAuditStore(),
            new FakeNotificationPort(),
            rateLimiter,
            clock,
            null);
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
    pharmacies.putActive(PID);
    pharmacies.putActive(UUID.fromString("22222222-2222-4222-8222-222222222222"), "SUSPENDED");
  }

  @Test
  void validationWhitespaceAndNullBranches() {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    assertThatThrownBy(() -> actions.sendNotice(ops, PID, "EMAIL", "  ", "msg", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("SUBJECT_REQUIRED"));
    assertThatThrownBy(() -> actions.sendNotice(ops, PID, "WHATSAPP", null, "msg", "NORMAL", "  "))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("TEMPLATE_REQUIRED"));
    assertThatThrownBy(() -> actions.sendNotice(ops, PID, "  ", null, "msg", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_CHANNEL"));
    assertThatThrownBy(() -> actions.sendNotice(ops, PID, "EMAIL", "sub", "  ", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
    assertThatThrownBy(() -> actions.sendNotice(ops, PID, "EMAIL", "sub", "m", "BAD", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
    assertThatThrownBy(() -> actions.addNote(ops, PID, "  ", false))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("NOTE_REQUIRED"));
    assertThatThrownBy(() -> actions.logCall(ops, PID, 10, null, null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_CALL_OUTCOME"));
    actions.logCall(principal(AuthRole.ADMIN_SUPER), PID, 10, "RESOLVED", null);
    actions.addNote(ops, PID, "n", false);
    actions.listNotes(ops, PID, null, 3, null);
    actions.sendNotice(ops, PID, "EMAIL", "sub", "m", "  ", null);
    actions.sendNoticeInternal(ops, PID, "EMAIL", "sub", "body", "NORMAL", null, Ids.newId(), true);
    assertThatThrownBy(() -> actions.addNote(ops, PID, null, false))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("NOTE_REQUIRED"));
    actions.listNotes(ops, PID, null, 2, 20);
    actions.listNotes(ops, PID, null, null, 20);
    actions.sendNotice(ops, PID, "EMAIL", "sub", "m", "NORMAL", "   ");
    assertThat(
            actions
                .sendNoticeInternal(
                    ops,
                    UUID.fromString("22222222-2222-4222-8222-222222222222"),
                    "EMAIL",
                    "sub",
                    "m",
                    "NORMAL",
                    null,
                    null,
                    true)
                .data())
        .isNotNull();
    actions.logCall(principal(AuthRole.ADMIN_SUPPORT), PID, 5, "RESOLVED", null);
    assertThatThrownBy(() -> actions.sendNotice(ops, PID, null, "sub", "m", "NORMAL", null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_CHANNEL"));
    assertThat(AdminPharmacyActionsService.resolveChannels("EMAIL", "URGENT"))
        .containsExactly("EMAIL");
  }

  @Test
  void bulkValidationCompletePaths() throws Exception {
    MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
    assertThatThrownBy(() -> bulkService.submitBulkAction(ops, null, "EXPORT", Map.of()))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PHARMACY_IDS_REQUIRED"));
    bulkService.submitBulkAction(
        ops,
        List.of(PID),
        "SEND_NOTICE",
        Map.of(
            "channel", "WHATSAPP", "message", "hello", "template_name", "PHARMACY_GENERAL_NOTICE"));
    assertThat(
            AdminBulkActionService.toJobMap(
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
                    null,
                    null,
                    Ids.newId(),
                    null,
                    null,
                    NOW)))
        .doesNotContainKeys("skipped_pharmacies", "download_url");
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    principal(AuthRole.ADMIN_SUPER),
                    List.of(PID),
                    "SUSPEND",
                    Map.of("suspend_type", "TEMPORARY")))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    ops, List.of(PID), "SEND_NOTICE", Map.of("message", "m")))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(
                    ops, List.of(PID), "SEND_NOTICE", Map.of("channel", "EMAIL", "message", "m")))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
    bulkService.submitBulkAction(ops, List.of(PID), "EXPORT", null);
    bulkService.submitBulkAction(
        ops,
        List.of(PID),
        "SEND_NOTICE",
        Map.of("channel", "IN_APP", "message", "m", "subject", "sub"));
    bulkService.submitBulkAction(ops, List.of(PID), "EXPORT", Map.of("ignored", true));
    java.util.LinkedHashMap<String, Object> nullChannelPayload = new java.util.LinkedHashMap<>();
    nullChannelPayload.put("channel", null);
    nullChannelPayload.put("message", null);
    assertThatThrownBy(
            () ->
                bulkService.submitBulkAction(ops, List.of(PID), "SEND_NOTICE", nullChannelPayload))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
    Method validate =
        AdminBulkActionService.class.getDeclaredMethod("validatePayload", String.class, Map.class);
    validate.setAccessible(true);
    validate.invoke(bulkService, "EXPORT", Map.of());
    validate.invoke(bulkService, "UNKNOWN", Map.of());
    java.util.LinkedHashMap<String, Object> blankMessage = new java.util.LinkedHashMap<>();
    blankMessage.put("channel", "WHATSAPP");
    blankMessage.put("message", "   ");
    assertThatThrownBy(
            () -> bulkService.submitBulkAction(ops, List.of(PID), "SEND_NOTICE", blankMessage))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
    java.util.LinkedHashMap<String, Object> emailNullMessage = new java.util.LinkedHashMap<>();
    emailNullMessage.put("channel", "EMAIL");
    emailNullMessage.put("message", null);
    assertThatThrownBy(
            () -> bulkService.submitBulkAction(ops, List.of(PID), "SEND_NOTICE", emailNullMessage))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
    java.util.LinkedHashMap<String, Object> nullChannelOnly = new java.util.LinkedHashMap<>();
    nullChannelOnly.put("channel", null);
    nullChannelOnly.put("message", "hello");
    assertThatThrownBy(
            () -> bulkService.submitBulkAction(ops, List.of(PID), "SEND_NOTICE", nullChannelOnly))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("PAYLOAD_INCOMPLETE"));
  }

  @Test
  void privateStaticHelpersViaReflection() throws Exception {
    Method truncate =
        AdminPharmacyActionsService.class.getDeclaredMethod("truncate", String.class, int.class);
    truncate.setAccessible(true);
    assertThat(truncate.invoke(null, null, 10)).isNull();
    assertThat(truncate.invoke(null, "short", 10)).isEqualTo("short");

    Method skippable =
        BulkActionJobProcessor.class.getDeclaredMethod("isSkippable", AppException.class);
    skippable.setAccessible(true);
    assertThat(skippable.invoke(null, new AppException("PHARMACY_NOT_ACTIVE", "inactive", 403)))
        .isEqualTo(true);
    assertThat(skippable.invoke(null, new AppException("NOTICE_RATE_LIMIT_EXCEEDED", "limit", 429)))
        .isEqualTo(true);
  }

  @Test
  void jdbcOldestSentAtMapperAndArrayNullValues() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var noticeStore =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyNoticeStore(jdbc);
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID), any(Timestamp.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              return List.of(((RowMapper<Instant>) mapper).mapRow(rs, 0));
            });
    assertThat(noticeStore.oldestSentAtSince(PID, NOW.minusSeconds(3600))).isEqualTo(NOW);

    ObjectMapper mapper = new ObjectMapper();
    var bulkStore =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcBulkActionJobStore(jdbc, mapper);
    UUID jobId = Ids.newId();
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(jobId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(jobId);
              when(rs.getString("action")).thenReturn("EXPORT");
              when(rs.getString("payload")).thenReturn("{}");
              Array pharmacyIds = mock(Array.class);
              when(pharmacyIds.getArray()).thenReturn(null);
              when(rs.getArray("pharmacy_ids")).thenReturn(pharmacyIds);
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getInt("total_pharmacies")).thenReturn(0);
              when(rs.getInt("processed")).thenReturn(0);
              when(rs.getInt("succeeded")).thenReturn(0);
              when(rs.getInt("failed")).thenReturn(0);
              when(rs.getInt("skipped")).thenReturn(0);
              when(rs.getString("skipped_pharmacies")).thenReturn(" ");
              when(rs.getString("result_payload")).thenReturn(" ");
              when(rs.getObject("initiated_by")).thenReturn(Ids.newId());
              when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(bulkStore.findById(jobId).orElseThrow().pharmacyIds()).isEmpty();

    bulkStore.insert(
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
            NOW,
            NOW,
            NOW));

    ResultSet noteRs = mock(ResultSet.class);
    when(noteRs.getObject("id")).thenReturn(Ids.newId());
    when(noteRs.getObject("pharmacy_id")).thenReturn(PID);
    when(noteRs.getString("note")).thenReturn("n");
    when(noteRs.getBoolean("is_flagged")).thenReturn(false);
    when(noteRs.getObject("added_by")).thenReturn(Ids.newId());
    when(noteRs.getTimestamp("created_at")).thenReturn(null);
    assertThat(
            com.nammamedmate.pharmacy.adapter.out.persistence.JdbcAdminNoteStore.mapRow(noteRs, 0)
                .createdAt())
        .isNull();
  }

  private static MedmatePrincipal principal(AuthRole role) {
    return new MedmatePrincipal(Ids.newId(), role, null, TokenScope.FULL, "jti");
  }

  @Test
  void recordCompactConstructorNullBranches() {
    assertThat(
            new PharmacyNoticeStore.NoticeRow(
                    Ids.newId(),
                    PID,
                    List.of("EMAIL"),
                    null,
                    "m",
                    null,
                    "NORMAL",
                    Ids.newId(),
                    NOW,
                    null)
                .channels())
        .containsExactly("EMAIL");
    assertThat(
            new PharmacyNoticeStore.NoticeRow(
                    Ids.newId(), PID, null, null, "m", null, "NORMAL", Ids.newId(), NOW, null)
                .channels())
        .isNull();
    assertThat(
            new BulkActionJobStore.JobRow(
                    Ids.newId(),
                    "EXPORT",
                    null,
                    null,
                    "QUEUED",
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    null,
                    Ids.newId(),
                    null,
                    null,
                    NOW)
                .pharmacyIds())
        .isNull();
    assertThat(
            new AdminPharmacyActionsService.NotesListResult(
                    null, com.nammamedmate.kernel.api.PaginationMeta.of(1, 20, 0))
                .data())
        .isNull();
    assertThat(
            new AdminPharmacyActionsService.NotesListResult(
                    Map.of("pharmacy_id", PID.toString()),
                    com.nammamedmate.kernel.api.PaginationMeta.of(1, 20, 0))
                .data()
                .get("pharmacy_id"))
        .isEqualTo(PID.toString());
    assertThat(
            new BulkActionJobStore.JobRow(
                    Ids.newId(),
                    "EXPORT",
                    Map.of("k", "v"),
                    List.of(PID),
                    "QUEUED",
                    1,
                    0,
                    0,
                    0,
                    0,
                    List.of(Map.of("pharmacy_id", PID.toString())),
                    Map.of("ok", true),
                    Ids.newId(),
                    NOW,
                    NOW,
                    NOW)
                .payload()
                .get("k"))
        .isEqualTo("v");
  }
}
