package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.ApiErrorRatePort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class StubApiErrorRateAdapter implements ApiErrorRatePort {

  private final CopyOnWriteArrayList<HotEndpoint> hot = new CopyOnWriteArrayList<>();

  @Override
  public List<HotEndpoint> endpointsAbove(BigDecimal errorRatePct, int windowMinutes) {
    List<HotEndpoint> out = new ArrayList<>();
    for (HotEndpoint e : hot) {
      if (e.errorRatePct().compareTo(errorRatePct) > 0) {
        out.add(e);
      }
    }
    return out;
  }

  public void setHot(List<HotEndpoint> endpoints) {
    hot.clear();
    hot.addAll(endpoints);
  }

  public void clear() {
    hot.clear();
  }
}
