package com.nammamedmate.analytics.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AdminReportStore {

  record ReportDefinition(
      String reportId,
      String name,
      String category,
      String description,
      String defaultCadence,
      String defaultFormat,
      int retentionYears,
      boolean active) {}

  record ScheduleRow(
      UUID id,
      String reportId,
      boolean enabled,
      String cadence,
      String format,
      List<String> emailRecipients,
      Instant nextRunAt,
      UUID updatedBy,
      Instant updatedAt) {
    public ScheduleRow {
      emailRecipients = emailRecipients == null ? List.of() : List.copyOf(emailRecipients);
    }
  }

  record JobRow(
      UUID id,
      String reportId,
      UUID triggeredBy,
      String triggerType,
      LocalDate periodFrom,
      LocalDate periodTo,
      String filtersJson,
      String format,
      String status,
      int progressPct,
      Integer rowCount,
      Integer fileSizeKb,
      String s3Key,
      String downloadUrl,
      Instant expiresAt,
      Instant queuedAt,
      Instant startedAt,
      Instant completedAt,
      String errorMessage) {}

  record HistoryRow(JobRow job, String reportName, String category, String generatedByLabel) {}

  record ReportRows(List<String> headers, List<List<String>> rows, long reconcileTotalPaise) {
    public ReportRows {
      headers = headers == null ? List.of() : List.copyOf(headers);
      rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public int size() {
      return rows.size();
    }
  }

  Optional<ReportDefinition> findDefinition(String reportId);

  List<ReportDefinition> listDefinitions(String categoryOrNull);

  Optional<ScheduleRow> findSchedule(String reportId);

  void upsertSchedule(ScheduleRow row);

  Instant lastCompletedAt(String reportId);

  void insertJob(JobRow job);

  Optional<JobRow> findJob(UUID jobId);

  int countActiveJobs(UUID triggeredBy);

  List<UUID> findQueuedJobIds(int limit);

  List<UUID> findTimedOutJobIds(Instant olderThan);

  void markJobRunning(UUID jobId, Instant startedAt);

  void markJobCompleted(
      UUID jobId,
      int progressPct,
      int rowCount,
      int fileSizeKb,
      String s3Key,
      String downloadUrl,
      Instant expiresAt,
      Instant completedAt);

  void markJobFailed(UUID jobId, String errorMessage, Instant completedAt);

  void refreshDownloadUrl(UUID jobId, String downloadUrl, Instant expiresAt);

  List<HistoryRow> listHistory(String categoryOrNull, Instant now, int limit, int offset);

  long countHistory(String categoryOrNull, Instant now);

  long estimateRows(String reportId, LocalDate from, LocalDate to, Map<String, Object> filters);

  ReportRows generateRows(
      String reportId, LocalDate from, LocalDate to, Map<String, Object> filters);

  long ledgerTcsTotalPaise(LocalDate from, LocalDate to);

  List<ScheduleRow> findDueSchedules(Instant now);
}
