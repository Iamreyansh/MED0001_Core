package com.nammamedmate.prescription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.PosDispensePort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrescriptionConfigTest {

  @Test
  void stubsAndClock() {
    PrescriptionConfig config = new PrescriptionConfig();
    assertThat(config.prescriptionClock()).isNotNull();
    assertThat(config.stubPrescriptionInUsePort().isInUse(UUID.randomUUID())).isFalse();
    assertThatThrownBy(
            () ->
                config
                    .stubOrderLinkPort()
                    .attachToCart(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CART_NOT_FOUND");

    assertThat(config.stubPharmacyPlanPort().rxQueueEnabled(UUID.randomUUID())).isTrue();
    config.stubOrderLinesPort().replaceOrderLines(UUID.randomUUID(), List.of());
    config.stubOrderStatusPort().markReadyForPickup(UUID.randomUUID());
    PosDispensePort pos = config.stubPosDispensePort();
    assertThat(pos.available()).isTrue();
    assertThat(pos.pushToBillingCart(UUID.randomUUID(), UUID.randomUUID(), List.of())).isNotNull();
    assertThat(pos.createSaleRecord(UUID.randomUUID(), UUID.randomUUID(), null, List.of()))
        .isNotNull();
    config
        .stubNotificationDispatchPort()
        .notifyCustomerRxRejected(UUID.randomUUID(), UUID.randomUUID(), "ILLEGIBLE", null);
    config
        .stubNotificationDispatchPort()
        .notifyPharmacyOwnerOverdue(UUID.randomUUID(), UUID.randomUUID());
    config
        .stubNotificationDispatchPort()
        .notifyComplianceOverdueAudit(UUID.randomUUID(), UUID.randomUUID());
    config
        .stubNotificationDispatchPort()
        .notifyHeadOfComplianceFlag(UUID.randomUUID(), "HIGH", "x");
    config
        .stubNotificationDispatchPort()
        .notifyComplianceDoctorScheduleAlert(UUID.randomUUID(), 51L);
    config
        .stubNotificationDispatchPort()
        .notifyComplianceDoctorBlacklisted(UUID.randomUUID(), "fraud");
    config
        .stubNotificationDispatchPort()
        .notifyComplianceFilingOverdue(UUID.randomUUID(), "SCHEDULE_H1_REGISTER", false);
    config
        .stubNotificationDispatchPort()
        .notifyPharmacyDrugRecall(UUID.randomUUID(), "Paracetamol 500mg", "PCM2024Q1");
    assertThat(config.stubInventoryBanPort().banByDrugNameAndBatch("x", "y").batchesBanned())
        .isZero();
    DoctorCardPort doctors = config.stubDoctorCardPort();
    assertThat(doctors.findForPrescription(UUID.randomUUID(), "E_PRESCRIPTION", "Dr X", null))
        .isPresent()
        .get()
        .extracting(DoctorCardPort.DoctorCard::verified)
        .isEqualTo(true);
    assertThat(doctors.findForPrescription(UUID.randomUUID(), "UPLOADED", "Dr Y", null))
        .isPresent()
        .get()
        .extracting(DoctorCardPort.DoctorCard::verified)
        .isEqualTo(false);
    config
        .stubScheduleRegisterWritePort()
        .recordDispense(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of());
    assertThat(config.stubInventoryBatchPort().findOpeningStock(UUID.randomUUID(), "x")).isEmpty();
    assertThat(config.stubInventoryStockPort().findByName(UUID.randomUUID(), "x")).isEmpty();
    assertThat(config.stubCustomerContactPort().find(UUID.randomUUID())).isEmpty();
    assertThat(
            config
                .stubCustomerContactPort()
                .previousOrdersCount(UUID.randomUUID(), UUID.randomUUID()))
        .isZero();
    assertThat(config.stubCatalogueSchedulePort().resolveSchedule("Alprazolam 0.5mg"))
        .contains("H1");
    assertThat(config.stubCatalogueSchedulePort().resolveSchedule("Morphine")).contains("X");
    assertThat(config.stubCatalogueSchedulePort().resolveSchedule("Metformin")).contains("H");
    assertThat(config.stubCatalogueSchedulePort().resolveSchedule("Paracetamol")).isEmpty();
    assertThat(config.stubCatalogueSchedulePort().resolveSchedule(null)).isEmpty();
  }
}
