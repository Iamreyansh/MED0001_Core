package com.nammamedmate.observability_ops.application.port.out;

import com.nammamedmate.observability_ops.domain.SloComplianceRecord;
import com.nammamedmate.observability_ops.domain.SloDefinition;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SloStore {

  List<SloDefinition> allDefinitions();

  Optional<SloDefinition> byMetricName(String metricName);

  /** Prior period actual % for trend (nullable if none). */
  Optional<BigDecimal> previousActualPct(String sloName);

  void insertHistory(SloComplianceRecord record);

  List<SloComplianceRecord> listHistory(String sloName, LocalDate periodFrom, LocalDate periodTo);
}
