package com.nammamedmate.marketing.application.port.out;

import com.nammamedmate.marketing.domain.CustomerMetrics;
import java.util.List;

/**
 * Customer order aggregates for segment compute. Stub returns empty until apps bridge wires JDBC.
 */
public interface OrderSegmentMetricsPort {

  List<CustomerMetrics> listAllActiveCustomers();
}
