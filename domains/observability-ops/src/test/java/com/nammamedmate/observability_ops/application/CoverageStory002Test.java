package com.nammamedmate.observability_ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.observability_ops.adapter.out.messaging.OutboxNotificationDispatchAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.InMemoryPlaybookAuditAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubApiErrorRateAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubOnlineAdminDirectoryAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubPaymentJobRetryAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubPharmacyThrottleAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubRiderNotifyAdapter;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Alerts;
import com.nammamedmate.observability_ops.application.InMemoryRemediationStores.Logs;
import com.nammamedmate.observability_ops.application.InMemoryRemediationStores.Playbooks;
import com.nammamedmate.observability_ops.application.port.out.ApiErrorRatePort;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.observability_ops.domain.RemediationActionType;
import com.nammamedmate.observability_ops.domain.RemediationLogEntry;
import com.nammamedmate.observability_ops.domain.RemediationStatus;
import com.nammamedmate.observability_ops.domain.RemediationTriggerType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CoverageStory002Test {

  private static final Instant T0 = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ZONE = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PLAYBOOK_ZONE = UUID.fromString("02000002-0001-4000-8000-000000000001");
  private static final UUID PLAYBOOK_FILL = UUID.fromString("02000002-0001-4000-8000-000000000002");

  private Playbooks playbooks;
  private Logs logs;
  private Alerts alerts;
  private StubRiderNotifyAdapter riders;
  private StubPharmacyThrottleAdapter pharmacies;
  private StubPaymentJobRetryAdapter payments;
  private StubApiErrorRateAdapter apiErrors;
  private InMemoryPlaybookAuditAdapter audit;
  private OutboxNotificationDispatchAdapter notify;
  private StubOnlineAdminDirectoryAdapter admins;
  private RemediationService remediation;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal ops;
  private MedmatePrincipal finance;

  @BeforeEach
  void setUp() {
    playbooks = new Playbooks();
    logs = new Logs();
    alerts = new Alerts();
    riders = new StubRiderNotifyAdapter(emptyOutbox());
    pharmacies = new StubPharmacyThrottleAdapter();
    payments = new StubPaymentJobRetryAdapter();
    apiErrors = new StubApiErrorRateAdapter();
    audit = new InMemoryPlaybookAuditAdapter();
    notify = new OutboxNotificationDispatchAdapter(emptyOutbox());
    admins = new StubOnlineAdminDirectoryAdapter();
    remediation =
        new RemediationService(
            playbooks,
            logs,
            alerts,
            riders,
            pharmacies,
            payments,
            apiErrors,
            audit,
            notify,
            admins,
            Clock.fixed(T0, ZoneOffset.UTC));
    superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  }

  @Test
  void enumsAndDomainRecords() {
    assertThat(RemediationActionType.values()).hasSize(6);
    assertThat(RemediationStatus.valueOf("SUCCESS")).isEqualTo(RemediationStatus.SUCCESS);
    assertThat(AlertType.API_ERROR_RATE_HIGH.name()).isEqualTo("API_ERROR_RATE_HIGH");
  }

  @Test
  void listPlaybooksAndFilters() {
    assertThat(remediation.listPlaybooks(ops).get("playbooks")).asList().hasSize(4);
    assertThatThrownBy(() -> remediation.listPlaybooks(finance))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    remediation.triggerManual(ops, "CLEAR_CACHE", "CACHE", UUID.randomUUID(), null);
    remediation.triggerManual(ops, "PAUSE_PROMOTION", "PROMOTION", UUID.randomUUID(), "x");
    assertThat(
            remediation
                .listActions(ops, "CLEAR_CACHE", "SUCCESS", T0.minusSeconds(10).toString(), null, 1)
                .data()
                .get("remediation_actions"))
        .asList()
        .isNotEmpty();
  }

  @Test
  void invalidInputs() {
    assertThatThrownBy(() -> remediation.triggerManual(ops, "NOPE", "ZONE", ZONE, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACTION_TYPE");
    assertThatThrownBy(
            () -> remediation.triggerManual(ops, "REQUEST_RIDERS", "ZONE", UUID.randomUUID(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ENTITY_NOT_FOUND");
    assertThatThrownBy(() -> remediation.triggerManual(ops, "REQUEST_RIDERS", "ZONE", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ENTITY_NOT_FOUND");
    assertThatThrownBy(() -> remediation.listActions(ops, "BAD", null, null, null, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACTION_TYPE");
    assertThatThrownBy(() -> remediation.patchPlaybook(ops, PLAYBOOK_ZONE, false, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> remediation.patchPlaybook(superAdmin, UUID.randomUUID(), false, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PLAYBOOK_NOT_FOUND");
    assertThatThrownBy(
            () ->
                remediation.patchPlaybook(
                    superAdmin, PLAYBOOK_ZONE, null, Map.of("dark_duration_minutes", 999)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_THRESHOLD");
  }

  @Test
  void apiErrorRateAndRecovery() {
    UUID entity = UUID.fromString("55555555-5555-4555-8555-555555555555");
    apiErrors.setHot(
        List.of(new ApiErrorRatePort.HotEndpoint("/api/v1/orders", new BigDecimal("12"), entity)));
    remediation.runAutoCycle();
    assertThat(notify.dispatched()).isNotEmpty();
    assertThat(logs.all()).isNotEmpty();

    UUID ph = UUID.fromString("66666666-6666-4666-8666-666666666666");
    pharmacies.put(ph, "Recover Me", new BigDecimal("85"), 0, 2, 14, true);
    pharmacies.throttleByPercent(ph, 0); // noop path — already throttled state set via put
    pharmacies.put(ph, "Recover Me", new BigDecimal("85"), 0, 2, 14, true);
    remediation.runAutoCycle();
    assertThat(pharmacies.candidatesForRecovery(new BigDecimal("80"), 2)).isEmpty();
  }

  @Test
  void paymentSuccessPathAndJobHelpers() {
    UUID jobId = UUID.fromString("77777777-7777-4777-8777-777777777777");
    payments.putFailed(jobId, T0.minusSeconds(600), 0);
    payments.setNextRetrySucceeds(true);
    remediation.runAutoCycle();
    assertThat(payments.jobExists(jobId)).isFalse();
    assertThat(logs.all().getFirst().status()).isEqualTo(RemediationStatus.SUCCESS);
  }

  @Test
  void zoneDarkWithoutTargetSkipped() {
    alerts.insert(
        new MonitoringAlert(
            UUID.randomUUID(),
            AlertSeverity.HIGH,
            AlertType.ZONE_DARK,
            "no zone",
            "rider_online_count",
            BigDecimal.ZERO,
            BigDecimal.ONE,
            null,
            T0,
            false,
            null,
            null,
            null,
            false,
            null,
            null));
    remediation.runAutoCycle();
    assertThat(logs.all()).isEmpty();
  }

  @Test
  void throttleManualAndEntityChecks() {
    UUID ph = UUID.fromString("22222222-2222-4222-8222-222222222222");
    Map<String, Object> res =
        remediation.triggerManual(ops, "THROTTLE_PHARMACY", "PHARMACY", ph, "manual");
    assertThat(res.get("action_type")).isEqualTo("THROTTLE_PHARMACY");
    assertThatThrownBy(
            () ->
                remediation.triggerManual(
                    ops, "RETRY_PAYMENT_JOB", "PAYMENT_JOB", UUID.randomUUID(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ENTITY_NOT_FOUND");
  }

  @Test
  void patchThresholdKeysAndListStatus() {
    remediation.patchPlaybook(
        superAdmin,
        PLAYBOOK_FILL,
        true,
        Map.of(
            "fill_rate_pct",
            65,
            "consecutive_days",
            4,
            "throttle_pct",
            25,
            "recovery_fill_rate_pct",
            85,
            "recovery_consecutive_days",
            3));
    remediation.patchPlaybook(
        superAdmin,
        UUID.fromString("02000002-0001-4000-8000-000000000003"),
        true,
        Map.of("retry_delay_minutes", 10, "max_retries", 5));
    remediation.patchPlaybook(
        superAdmin,
        UUID.fromString("02000002-0001-4000-8000-000000000004"),
        true,
        Map.of("error_rate_pct", 8, "window_minutes", 10));
    assertThat(audit.entries()).hasSize(3);
    remediation.triggerManual(ops, "REQUEST_RIDERS", null, ZONE, "x");
    assertThat(remediation.listActions(ops, null, "SUCCESS", null, null, 1).data())
        .containsKey("remediation_actions");
  }

  @Test
  void branchCoverageExtras() {
    // page < 1, blank reason, blank action, invalid status, null principal
    remediation.triggerManual(ops, "REQUEST_RIDERS", "ZONE", ZONE, "   ");
    assertThat(remediation.listActions(ops, null, null, null, null, 0).data())
        .containsKey("remediation_actions");
    assertThatThrownBy(() -> remediation.triggerManual(ops, "  ", "ZONE", ZONE, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACTION_TYPE");
    assertThatThrownBy(() -> remediation.triggerManual(ops, null, "ZONE", ZONE, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACTION_TYPE");
    assertThatThrownBy(() -> remediation.listActions(ops, null, "NOPE", null, null, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACTION_TYPE");
    assertThatThrownBy(() -> remediation.listPlaybooks(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    // threshold non-number + out of range
    assertThatThrownBy(
            () ->
                remediation.patchPlaybook(
                    superAdmin, PLAYBOOK_ZONE, null, Map.of("dark_duration_minutes", "x")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_THRESHOLD");
    assertThatThrownBy(
            () ->
                remediation.patchPlaybook(
                    superAdmin, PLAYBOOK_ZONE, null, Map.of("dark_duration_minutes", 0)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_THRESHOLD");
    assertThatThrownBy(
            () ->
                remediation.patchPlaybook(
                    superAdmin, PLAYBOOK_ZONE, null, Map.of("dark_duration_minutes", 999)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_THRESHOLD");
    remediation.patchPlaybook(superAdmin, PLAYBOOK_ZONE, null, Map.of("dark_duration_minutes", 45));

    // disable fill + payment playbooks early-return
    remediation.patchPlaybook(superAdmin, PLAYBOOK_FILL, false, null);
    remediation.patchPlaybook(
        superAdmin, UUID.fromString("02000002-0001-4000-8000-000000000003"), false, null);
    remediation.runAutoCycle();

    // re-enable and hit already-autoRemediated fill alert + exhausted payment at gate
    remediation.patchPlaybook(superAdmin, PLAYBOOK_FILL, true, null);
    remediation.patchPlaybook(
        superAdmin, UUID.fromString("02000002-0001-4000-8000-000000000003"), true, null);
    UUID ph = UUID.fromString("88888888-8888-4888-8888-888888888888");
    pharmacies.put(ph, "Already", new BigDecimal("50"), 5, 0, 20, false);
    MonitoringAlert existing =
        alerts.insert(
            new MonitoringAlert(
                UUID.randomUUID(),
                AlertSeverity.HIGH,
                AlertType.LOW_FILL_RATE,
                "m",
                "fill_rate_pct",
                new BigDecimal("50"),
                new BigDecimal("70"),
                ph,
                T0,
                false,
                null,
                null,
                null,
                true,
                null,
                null));
    assertThat(existing.autoRemediated()).isTrue();
    UUID job = UUID.fromString("99999999-9999-4999-8999-999999999999");
    payments.putFailed(job, T0.minusSeconds(600), 3);
    remediation.runAutoCycle();
    assertThat(alerts.findOpen(AlertType.PAYMENT_JOB_FAILURE, job).orElseThrow().severity())
        .isEqualTo(AlertSeverity.CRITICAL);

    // open alert autoRemediated skip + GMV with no playbook + dedicated types skip
    alerts.insert(
        new MonitoringAlert(
            UUID.randomUUID(),
            AlertSeverity.HIGH,
            AlertType.ZONE_DARK,
            "done",
            "rider_online_count",
            BigDecimal.ZERO,
            BigDecimal.ONE,
            ZONE,
            T0,
            false,
            null,
            null,
            null,
            true,
            null,
            null));
    alerts.insert(
        new MonitoringAlert(
            UUID.randomUUID(),
            AlertSeverity.CRITICAL,
            AlertType.GMV_DROP,
            "gmv",
            "gmv",
            BigDecimal.ONE,
            BigDecimal.TEN,
            null,
            T0,
            false,
            null,
            null,
            null,
            false,
            null,
            null));
    alerts.insert(
        new MonitoringAlert(
            UUID.randomUUID(),
            AlertSeverity.HIGH,
            AlertType.PAYMENT_JOB_FAILURE,
            "skip",
            "p",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            job,
            T0,
            false,
            null,
            null,
            null,
            false,
            null,
            null));
    remediation.runAutoCycle();

    assertThatThrownBy(() -> remediation.patchPlaybook(null, PLAYBOOK_ZONE, false, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    remediation.patchPlaybook(superAdmin, PLAYBOOK_ZONE, false, null);
    remediation.patchPlaybook(superAdmin, PLAYBOOK_ZONE, true, Map.of());

    assertThatThrownBy(() -> remediation.triggerManual(ops, "", "ZONE", ZONE, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACTION_TYPE");
    remediation.listActions(ops, "", null, null, null, 1);
    remediation.listActions(ops, null, "", null, null, 1);
    remediation.listActions(ops, null, null, "", null, 1);

    // disable API playbook → !enabled return
    remediation.patchPlaybook(
        superAdmin, UUID.fromString("02000002-0001-4000-8000-000000000004"), false, null);
    remediation.runAutoCycle();
    remediation.patchPlaybook(
        superAdmin, UUID.fromString("02000002-0001-4000-8000-000000000004"), true, null);

    // LOW_FILL open (not remediated) → processOpenAlerts skip
    alerts.insert(
        new MonitoringAlert(
            UUID.randomUUID(),
            AlertSeverity.HIGH,
            AlertType.LOW_FILL_RATE,
            "skip-open",
            "fill_rate_pct",
            new BigDecimal("50"),
            new BigDecimal("70"),
            UUID.fromString("91919191-9191-4919-8919-919191919191"),
            T0,
            false,
            null,
            null,
            null,
            false,
            null,
            null));
    remediation.runAutoCycle();

    // open API_ERROR_RATE_HIGH skipped in processOpenAlerts (not remediated, no hot endpoints)
    UUID apiEntity = UUID.fromString("acacacac-acac-4cac-8cac-acacacacacac");
    alerts.insert(
        new MonitoringAlert(
            UUID.randomUUID(),
            AlertSeverity.CRITICAL,
            AlertType.API_ERROR_RATE_HIGH,
            "api",
            "api_error_rate_pct",
            new BigDecimal("9"),
            new BigDecimal("5"),
            apiEntity,
            T0,
            false,
            null,
            null,
            null,
            false,
            null,
            null));
    apiErrors.clear();
    remediation.runAutoCycle();
    // open present + not remediated + hot → executeAuto path
    apiErrors.setHot(
        List.of(new ApiErrorRatePort.HotEndpoint("/z", new BigDecimal("9"), apiEntity)));
    remediation.runAutoCycle();
    // already-remediated + hot → continue
    apiErrors.setHot(
        List.of(new ApiErrorRatePort.HotEndpoint("/z", new BigDecimal("9"), apiEntity)));
    remediation.runAutoCycle();
    // open empty + hot → create path
    UUID apiNew = UUID.fromString("adadadad-adad-4dad-8dad-adadadadadad");
    apiErrors.setHot(
        List.of(new ApiErrorRatePort.HotEndpoint("/new", new BigDecimal("11"), apiNew)));
    remediation.runAutoCycle();

    // empty thresholds → number/intVal defaults
    playbooks.update(PLAYBOOK_FILL, true, Map.of(), superAdmin.subject(), T0);
    pharmacies.put(
        UUID.fromString("87878787-8787-4878-8878-878787878787"),
        "Defaults",
        new BigDecimal("50"),
        5,
        0,
        20,
        false);
    remediation.runAutoCycle();

    // defaultEntityType via null entity type for each action
    UUID phDefault = UUID.fromString("22222222-2222-4222-8222-222222222222");
    remediation.triggerManual(ops, "THROTTLE_PHARMACY", null, phDefault, null);
    UUID job2 = UUID.fromString("cdcdcdcd-cdcd-4dcd-8dcd-cdcdcdcdcdcd");
    payments.putFailed(job2, T0.minusSeconds(600), 0);
    payments.setNextRetrySucceeds(true);
    remediation.triggerManual(ops, "RETRY_PAYMENT_JOB", "  ", job2, null);
    remediation.triggerManual(ops, "PAGE_ON_CALL", null, UUID.randomUUID(), null);
    remediation.triggerManual(ops, "CLEAR_CACHE", null, UUID.randomUUID(), null);
    remediation.triggerManual(ops, "PAUSE_PROMOTION", null, UUID.randomUUID(), null);

    // throttle miss — unique pharmacy so rate-limit from earlier THROTTLE doesn't apply
    UUID phMiss = UUID.fromString("23232323-2323-4232-8232-232323232323");
    pharmacies.put(phMiss, "Miss Me", new BigDecimal("90"), 0, 0, 10, false);
    pharmacies.setForceThrottleMiss(true);
    assertThatThrownBy(
            () -> remediation.triggerManual(ops, "THROTTLE_PHARMACY", "PHARMACY", phMiss, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ENTITY_NOT_FOUND");
    pharmacies.setForceThrottleMiss(false);

    // list INITIATED with null completed_at + playbooks with/without last_triggered
    logs.insert(
        new RemediationLogEntry(
            UUID.randomUUID(),
            null,
            null,
            RemediationActionType.CLEAR_CACHE,
            RemediationTriggerType.MANUAL,
            "CACHE",
            UUID.randomUUID(),
            Map.of(),
            RemediationStatus.INITIATED,
            ops.subject(),
            T0,
            null,
            null));
    assertThat(remediation.listActions(ops, null, "INITIATED", null, null, null).data())
        .containsKey("remediation_actions");
    assertThat(remediation.listPlaybooks(ops).get("playbooks")).asList().isNotEmpty();

    // patch with null isEnabled only
    remediation.patchPlaybook(superAdmin, PLAYBOOK_ZONE, null, Map.of("dark_duration_minutes", 40));

    // domain null-map compactors
    assertThat(
            new com.nammamedmate.observability_ops.domain.RemediationPlaybook(
                    UUID.randomUUID(),
                    AlertType.ZONE_DARK,
                    RemediationActionType.REQUEST_RIDERS,
                    "d",
                    null,
                    true,
                    null,
                    null,
                    T0)
                .threshold())
        .isEmpty();
    assertThat(
            new com.nammamedmate.observability_ops.domain.RemediationLogEntry(
                    UUID.randomUUID(),
                    null,
                    null,
                    RemediationActionType.CLEAR_CACHE,
                    RemediationTriggerType.MANUAL,
                    "CACHE",
                    UUID.randomUUID(),
                    null,
                    RemediationStatus.SUCCESS,
                    ops.subject(),
                    T0,
                    null,
                    null)
                .actionDetails())
        .isEmpty();

    // empty playbook lookups last (isEmpty returns)
    playbooks.removeByAlertType(AlertType.LOW_FILL_RATE);
    playbooks.removeByAlertType(AlertType.PAYMENT_JOB_FAILURE);
    playbooks.removeByAlertType(AlertType.API_ERROR_RATE_HIGH);
    playbooks.removeByAlertType(AlertType.ZONE_DARK);
    alerts.insert(
        new MonitoringAlert(
            UUID.randomUUID(),
            AlertSeverity.HIGH,
            AlertType.ZONE_DARK,
            "orphan",
            "r",
            BigDecimal.ZERO,
            BigDecimal.ONE,
            ZONE,
            T0,
            false,
            null,
            null,
            null,
            false,
            null,
            null));
    remediation.runAutoCycle();
  }

  private static ObjectProvider<com.nammamedmate.messaging.OutboxPublisher> emptyOutbox() {
    return new ObjectProvider<>() {
      @Override
      public com.nammamedmate.messaging.OutboxPublisher getObject() {
        return null;
      }

      @Override
      public com.nammamedmate.messaging.OutboxPublisher getIfAvailable() {
        return null;
      }

      @Override
      public com.nammamedmate.messaging.OutboxPublisher getIfUnique() {
        return null;
      }
    };
  }
}
