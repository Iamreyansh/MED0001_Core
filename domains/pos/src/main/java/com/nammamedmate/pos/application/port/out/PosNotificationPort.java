package com.nammamedmate.pos.application.port.out;

import com.nammamedmate.pos.domain.ShareChannel;
import java.time.Instant;
import java.util.UUID;

public interface PosNotificationPort {

  record ShareResult(String messageId, Instant sentAt) {}

  /**
   * Dispatch invoice share. Throws AppException CHANNEL_UNAVAILABLE when notifications disabled.
   * Payload must stay ids-only when enqueueing outbox.
   */
  ShareResult shareInvoice(
      UUID pharmacyId,
      UUID invoiceId,
      String invoiceNumber,
      ShareChannel channel,
      String recipient,
      String pdfUrl);

  /** Khata payment reminder (WhatsApp/SMS). Ids-only outbox payload. */
  ShareResult sendKhataReminder(
      UUID pharmacyId,
      UUID customerId,
      ShareChannel channel,
      String template,
      String recipient,
      long outstandingPaise);
}
