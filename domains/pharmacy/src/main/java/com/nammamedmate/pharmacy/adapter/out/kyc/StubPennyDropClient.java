package com.nammamedmate.pharmacy.adapter.out.kyc;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.application.port.out.PennyDropPort;
import java.util.UUID;

/** ponytail: always returns PENDING reference; webhook worker marks VERIFIED/FAILED later. */
public final class StubPennyDropClient implements PennyDropPort {

  @Override
  public PennyDropResult initiate(UUID pharmacyId, String ifscCode, String accountNumberLast4) {
    return new PennyDropResult("RZP-PENNY-" + Ids.newId(), "PENDING");
  }
}
