package com.nammamedmate.support.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketMessage(
    UUID id,
    UUID ticketId,
    SenderType senderType,
    UUID senderId,
    String senderName,
    String message,
    boolean internalNote,
    UUID cannedResponseId,
    List<String> attachments,
    Instant createdAt) {

  public TicketMessage {
    attachments =
        attachments == null
            ? List.of()
            : List.copyOf(attachments.stream().filter(a -> a != null).toList());
  }
}
