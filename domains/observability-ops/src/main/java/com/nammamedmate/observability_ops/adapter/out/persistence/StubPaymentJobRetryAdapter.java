package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.PaymentJobRetryPort;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class StubPaymentJobRetryAdapter implements PaymentJobRetryPort {

  private final Map<UUID, JobState> jobs = new ConcurrentHashMap<>();
  private boolean nextRetrySucceeds = false;

  @Override
  public boolean jobExists(UUID jobId) {
    return jobs.containsKey(jobId);
  }

  @Override
  public List<FailedJob> jobsReadyForRetry(Instant now, int delayMinutes, int maxRetries) {
    List<FailedJob> out = new ArrayList<>();
    for (Map.Entry<UUID, JobState> e : jobs.entrySet()) {
      JobState s = e.getValue();
      if (s.exhausted()) {
        continue;
      }
      if (s.failedRetryCount() >= maxRetries) {
        out.add(new FailedJob(e.getKey(), s.failedAt(), s.failedRetryCount()));
        continue;
      }
      Instant readyAt = s.failedAt().plus(delayMinutes, ChronoUnit.MINUTES);
      if (!readyAt.isAfter(now)) {
        out.add(new FailedJob(e.getKey(), s.failedAt(), s.failedRetryCount()));
      }
    }
    return out;
  }

  @Override
  public boolean retry(UUID jobId) {
    JobState s = jobs.get(jobId);
    if (s == null || s.exhausted()) {
      return false;
    }
    if (nextRetrySucceeds) {
      jobs.remove(jobId);
      return true;
    }
    jobs.put(jobId, new JobState(s.failedAt(), s.failedRetryCount() + 1, false));
    return false;
  }

  @Override
  public void markExhausted(UUID jobId) {
    JobState s = jobs.get(jobId);
    if (s != null) {
      jobs.put(jobId, new JobState(s.failedAt(), s.failedRetryCount(), true));
    }
  }

  @Override
  public int failedRetryCount(UUID jobId) {
    JobState s = jobs.get(jobId);
    return s == null ? -1 : s.failedRetryCount();
  }

  public void putFailed(UUID jobId, Instant failedAt, int failedRetryCount) {
    jobs.put(jobId, new JobState(failedAt, failedRetryCount, false));
  }

  public void setNextRetrySucceeds(boolean value) {
    this.nextRetrySucceeds = value;
  }

  private record JobState(Instant failedAt, int failedRetryCount, boolean exhausted) {}
}
