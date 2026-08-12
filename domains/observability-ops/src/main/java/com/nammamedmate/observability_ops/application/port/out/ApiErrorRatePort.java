package com.nammamedmate.observability_ops.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ApiErrorRatePort {

  record HotEndpoint(String endpoint, BigDecimal errorRatePct, UUID syntheticEntityId) {}

  List<HotEndpoint> endpointsAbove(BigDecimal errorRatePct, int windowMinutes);
}
