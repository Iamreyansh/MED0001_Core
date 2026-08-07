package com.nammamedmate.catalogue.adapter.out.medicine;

import com.nammamedmate.catalogue.application.port.out.BanMappingHidePort;
import java.util.UUID;

/** No-op until STORY-005 creates pharmacy_catalogue_mapping. */
public class StubBanMappingHideClient implements BanMappingHidePort {

  @Override
  public int hideAllForMedicine(UUID medicineId) {
    return 0;
  }
}
