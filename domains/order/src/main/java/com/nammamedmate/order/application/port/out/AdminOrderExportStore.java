package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.AdminOrderExportJob;
import com.nammamedmate.order.domain.ExportJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminOrderExportStore {

  AdminOrderExportJob insert(AdminOrderExportJob job);

  Optional<AdminOrderExportJob> findById(UUID jobId);

  List<AdminOrderExportJob> findByStatus(ExportJobStatus status, int limit);

  void markReady(UUID jobId, String s3Key, int rowCount, Instant completedAt);

  void markFailed(UUID jobId, Instant completedAt);
}
