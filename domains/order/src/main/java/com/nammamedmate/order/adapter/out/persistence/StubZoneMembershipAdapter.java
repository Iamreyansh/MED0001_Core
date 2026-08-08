package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.ZoneMembershipPort;
import java.util.UUID;

/** ponytail: always in-zone until pharmacy zone membership is wired into order. */
public class StubZoneMembershipAdapter implements ZoneMembershipPort {

  @Override
  public boolean isInPharmacyZone(UUID pharmacyId, double lat, double lng) {
    return pharmacyId != null;
  }
}
