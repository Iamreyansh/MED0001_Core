package com.nammamedmate.payment.application.port.out;

import com.nammamedmate.payment.domain.Payment;
import java.util.Optional;
import java.util.UUID;

public interface PaymentStore {

  Payment insert(Payment payment);

  Payment update(Payment payment);

  Optional<Payment> findById(UUID paymentId);

  Optional<Payment> findByOrderId(UUID orderId);

  Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

  Optional<Payment> findByRazorpayPaymentId(String razorpayPaymentId);
}
