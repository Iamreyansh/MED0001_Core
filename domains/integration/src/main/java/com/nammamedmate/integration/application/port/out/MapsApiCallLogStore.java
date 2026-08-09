package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.MapsApiCallLog;
import java.math.BigDecimal;
import java.time.Instant;

public interface MapsApiCallLogStore {

  void insert(MapsApiCallLog log);

  BigDecimal sumEstimatedCostSince(Instant since);
}
