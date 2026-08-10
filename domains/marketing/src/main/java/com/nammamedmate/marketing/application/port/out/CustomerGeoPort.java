package com.nammamedmate.marketing.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** City / pincode for customers (from addresses). Stub empty until bridge. */
public interface CustomerGeoPort {

  record Geo(String city, String pincode) {}

  Map<UUID, Geo> findByCustomerIds(Collection<UUID> customerIds);
}
