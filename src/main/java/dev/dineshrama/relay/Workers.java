package dev.dineshrama.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Workers {
  private static final Logger LOG = LoggerFactory.getLogger(Workers.class);
  private final TransferService service;

  public Workers(TransferService service) {
    this.service = service;
  }

  @Scheduled(
      fixedDelayString = "${relay.worker-delay-ms:1500}",
      initialDelayString = "${relay.worker-initial-delay-ms:1500}")
  public void tick() {
    try {
      service.verifyOne();
      service.cleanOne();
    } catch (RuntimeException e) {
      LOG.warn(
          "Transfer worker transaction failed; durable state remains available for retry ({})",
          e.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelay = 900000, initialDelay = 30000)
  public void sweepStaging() {
    try {
      service.sweepStaging();
    } catch (java.io.IOException e) {
      LOG.warn("Staging sweep will retry on its next scheduled run");
    }
  }
}
