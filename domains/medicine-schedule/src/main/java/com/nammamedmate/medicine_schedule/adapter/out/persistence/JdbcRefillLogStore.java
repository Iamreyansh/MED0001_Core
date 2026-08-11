package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import com.nammamedmate.medicine_schedule.application.port.out.RefillLogStore;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcRefillLogStore implements RefillLogStore {

  private final JdbcTemplate jdbc;

  public JdbcRefillLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(RefillLogRecord log) {
    jdbc.update(
        """
        INSERT INTO refill_log (
          id, medicine_id, customer_id, units_added, units_before, units_after,
          refill_date, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        log.id(),
        log.medicineId(),
        log.customerId(),
        log.unitsAdded(),
        log.unitsBefore(),
        log.unitsAfter(),
        Date.valueOf(log.refillDate()),
        Timestamp.from(log.createdAt()));
  }

  @Override
  public boolean existsNegativeOnDate(UUID medicineId, LocalDate refillDate) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM refill_log
            WHERE medicine_id = ? AND refill_date = ? AND units_added < 0
            """,
            Integer.class,
            medicineId,
            Date.valueOf(refillDate));
    return count != null && count > 0;
  }
}
