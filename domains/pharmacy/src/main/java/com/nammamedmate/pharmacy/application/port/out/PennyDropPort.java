package com.nammamedmate.pharmacy.application.port.out;

import java.util.UUID;

/** CashfreePayout penny-drop stub until payment integration lands. */
public interface PennyDropPort {

  record PennyDropResult(String referenceId, String status) {}

  PennyDropResult initiate(UUID pharmacyId, String ifscCode, String accountNumberLast4);
}
