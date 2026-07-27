package com.nammamedmate.pharmacy.application;

import com.nammamedmate.pharmacy.application.port.out.KycVerificationStore;
import com.nammamedmate.pharmacy.application.port.out.KycVerificationStore.KycVerificationRecord;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/** Processes due KYC verification retries outside a single mega-transaction. */
@Component
public class AutoKycRetryWorker {

  private final AutoKycService autoKyc;
  private final KycVerificationStore verifications;
  private final Clock clock;

  public AutoKycRetryWorker(
      AutoKycService autoKyc, KycVerificationStore verifications, Clock clock) {
    this.autoKyc = autoKyc;
    this.verifications = verifications;
    this.clock = clock;
  }

  public int processDueRetries() {
    List<KycVerificationRecord> due = verifications.findDueRetries(clock.instant(), 25);
    for (KycVerificationRecord record : due) {
      autoKyc.processAsyncCheck(record.id());
    }
    return due.size();
  }
}
