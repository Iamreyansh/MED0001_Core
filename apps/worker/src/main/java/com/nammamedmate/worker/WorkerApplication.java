package com.nammamedmate.worker;

import com.nammamedmate.notification.NotificationConfig;
import com.nammamedmate.pharmacy.PharmacyConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * Minimal worker composition root so ECS can reach services-stable.
 *
 * <p>Wires SQS poll + notification push/SMS + KYC malware (WorkerPharmacyConfig). Other domain
 * consumers in {@link DomainEventRouter} are {@code ObjectProvider}-optional and stay unbound until
 * each domain gets an explicit worker wiring module (scanning {@code *.application} crash-loops on
 * API-only ports).
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {
      "com.nammamedmate.worker",
      "com.nammamedmate.notification.adapter.in.messaging",
      "com.nammamedmate.notification.application",
      "com.nammamedmate.notification.adapter.out.persistence"
    },
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ".*\\.(adapter\\.in\\.web|web)\\..*"))
@Import({NotificationConfig.class, PharmacyConfig.class})
public class WorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(WorkerApplication.class, args);
  }
}
