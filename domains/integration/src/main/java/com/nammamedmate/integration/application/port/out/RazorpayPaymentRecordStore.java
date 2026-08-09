package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.RazorpayPaymentRecord;
import java.util.Optional;
import java.util.UUID;

public interface RazorpayPaymentRecordStore {

  void insert(RazorpayPaymentRecord record);

  void update(RazorpayPaymentRecord record);

  Optional<RazorpayPaymentRecord> findById(UUID id);

  Optional<RazorpayPaymentRecord> findByRazorpayOrderId(String razorpayOrderId);

  Optional<RazorpayPaymentRecord> findByRazorpayPaymentId(String razorpayPaymentId);
}
