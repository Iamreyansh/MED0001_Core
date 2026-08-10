package com.nammamedmate.api.config;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.prescription.application.EPrescriptionService;
import com.nammamedmate.prescription.application.EPrescriptionService.CreateCommand;
import com.nammamedmate.prescription.application.EPrescriptionService.Created;
import com.nammamedmate.prescription.application.port.out.OrderLinkPort;
import com.nammamedmate.prescription.domain.EPrescriptionSignature.MedicinePrescribed;
import com.nammamedmate.teleconsult.application.port.out.CartLinkPort;
import com.nammamedmate.teleconsult.application.port.out.CartPort;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.Issued;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.MedicineLine;
import com.nammamedmate.teleconsult.application.port.out.NotificationDispatchPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridges for teleconsult: cart ACTIVE ownership check, cart e-Rx attach, e-Rx
 * write + doctor registry upsert, outbox push notifications.
 */
@Configuration
public class TeleconsultBridgeConfig {

  @Bean
  @Primary
  CartPort jdbcTeleconsultCartPort(JdbcTemplate jdbc) {
    return (cartId, customerId) -> {
      if (cartId == null || customerId == null) {
        return false;
      }
      Boolean exists =
          jdbc.queryForObject(
              """
              SELECT EXISTS(
                SELECT 1 FROM carts
                WHERE id = ? AND customer_id = ? AND status = 'ACTIVE'
              )
              """,
              Boolean.class,
              cartId,
              customerId);
      return Boolean.TRUE.equals(exists);
    };
  }

  @Bean
  @Primary
  CartLinkPort jdbcTeleconsultCartLinkPort(OrderLinkPort orderLink) {
    return orderLink::attachToCart;
  }

  @Bean
  @Primary
  EPrescriptionWritePort jdbcEPrescriptionWritePort(EPrescriptionService ePrescriptionService) {
    return request -> {
      List<MedicinePrescribed> meds =
          request.medicines().stream()
              .map(
                  m ->
                      new MedicinePrescribed(
                          m.name(),
                          m.dosage(),
                          m.frequency(),
                          m.quantity(),
                          m.unit(),
                          m.durationDays(),
                          m.notes()))
              .toList();
      Created created =
          ePrescriptionService.createFromTeleconsult(
              new CreateCommand(
                  request.id(),
                  request.customerId(),
                  request.teleconsultId(),
                  request.doctorId(),
                  request.doctorName(),
                  request.qualification(),
                  request.registrationNo(),
                  request.specialty(),
                  request.patientName(),
                  meds,
                  request.adviceOnly(),
                  request.adviceText(),
                  request.clinicalNotes(),
                  request.issuedAt()));
      List<MedicineLine> lines =
          created.medicines().stream()
              .map(
                  m ->
                      new MedicineLine(
                          m.name(),
                          m.dosage(),
                          m.frequency(),
                          m.quantity(),
                          m.unit(),
                          m.durationDays(),
                          m.notes()))
              .toList();
      return new Issued(
          created.prescriptionId(),
          created.rxId(),
          created.digitalSignatureHash(),
          created.expiresAt(),
          created.issuedAt(),
          lines);
    };
  }

  @Bean
  @Primary
  NotificationDispatchPort outboxTeleconsultNotificationPort(OutboxPublisher outbox) {
    return new NotificationDispatchPort() {
      @Override
      public void notifyConsultAutoCancelled(UUID customerId, UUID consultId) {
        Map<String, Object> payload = basePayload(customerId, consultId);
        payload.put("template", "TELECONSULT_AUTO_CANCELLED");
        outbox.publish(
            DomainEvent.of(
                "teleconsult.notification.auto_cancelled", "teleconsult", consultId, payload));
      }

      @Override
      public void notifyConsultStatusUpdated(UUID customerId, UUID consultId, String status) {
        Map<String, Object> payload = basePayload(customerId, consultId);
        payload.put("template", "TELECONSULT_STATUS_UPDATED");
        payload.put("status", status);
        outbox.publish(
            DomainEvent.of(
                "teleconsult.notification.status_updated", "teleconsult", consultId, payload));
      }

      private Map<String, Object> basePayload(UUID customerId, UUID consultId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("customer_id", customerId.toString());
        payload.put("consult_id", consultId.toString());
        payload.put("channels", List.of("PUSH"));
        return payload;
      }
    };
  }
}
