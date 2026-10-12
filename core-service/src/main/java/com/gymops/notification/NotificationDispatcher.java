package com.gymops.notification;

import com.gymops.config.AppProperties;
import com.gymops.notification.channel.DeliveryReceipt;
import com.gymops.notification.channel.NotificationSender;
import com.gymops.notification.channel.PermanentDeliveryException;
import com.gymops.notification.channel.RecipientUnusableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the notification outbox. Rows are claimed with FOR UPDATE SKIP LOCKED, so any number of
 * application instances can dispatch concurrently without ShedLock and without double delivery.
 * Failures are retried with exponential backoff; channels without a sender are marked FAILED. Before an external
 * delivery the {@link DeliveryPolicy} re-checks member preferences and quiet hours (SKIPPED / deferred).
 */
@Slf4j
@Component
public class NotificationDispatcher {

    private final NotificationRepository repository;
    private final Map<NotificationChannel, NotificationSender> senders = new EnumMap<>(NotificationChannel.class);
    private final TransactionTemplate tx;
    private final AppProperties.Notifications props;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final DeliveryPolicy policy;

    public NotificationDispatcher(NotificationRepository repository, List<NotificationSender> senderBeans,
                                  TransactionTemplate tx, AppProperties appProperties, MeterRegistry meterRegistry,
                                  Clock clock, DeliveryPolicy policy) {
        this.repository = repository;
        this.policy = policy;
        senderBeans.forEach(s -> senders.put(s.channel(), s));
        this.tx = tx;
        this.props = appProperties.notifications();
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.notification-dispatch-interval:PT15S}", initialDelayString = "PT20S")
    public void dispatch() {
        Integer processed;
        do {
            processed = tx.execute(status -> dispatchBatch());
        } while (processed != null && processed == props.dispatchBatchSize());
    }

    int dispatchBatch() {
        Instant now = Instant.now(clock);
        List<Notification> batch = repository.claimDue(now, props.dispatchBatchSize());
        batch.forEach(n -> process(n, now));
        return batch.size();
    }

    /**
     * Delivers one notification right away (test sends), with the same locking and bookkeeping as the scheduled
     * dispatcher. Returns false when the row is not PENDING or another dispatcher holds it.
     */
    public boolean dispatchNow(Long id) {
        Boolean done = tx.execute(status -> repository.claimById(id).map(n -> {
            process(n, Instant.now(clock));
            return true;
        }).orElse(false));
        return Boolean.TRUE.equals(done);
    }

    private void process(Notification n, Instant now) {
        String channel = n.getChannel().name();
        DeliveryPolicy.Decision decision = policy.decide(n, now);
        if (decision instanceof DeliveryPolicy.Skip skip) {
            n.markSkipped(skip.reason());
            count(channel, "skipped");
            return;
        }
        if (decision instanceof DeliveryPolicy.Defer defer) {
            n.deferUntil(defer.until());
            count(channel, "deferred");
            return;
        }
        NotificationSender sender = senders.get(n.getChannel());
        try {
            if (sender == null) {
                throw new IllegalStateException("No sender configured for channel " + n.getChannel());
            }
            DeliveryReceipt receipt = sender.deliver(n);
            n.markSent(now, receipt.provider(), receipt.providerMessageId(), receipt.segments());
            count(channel, "sent");
        } catch (RecipientUnusableException e) {
            n.markSkipped(e.getMessage());
            count(channel, "skipped");
            log.info("Notification {} skipped: {}", n.getId(), e.getMessage());
        } catch (PermanentDeliveryException e) {
            n.markFailedPermanently(e.getMessage());
            count(channel, "failed");
            log.warn("Notification {} rejected by provider: {}", n.getId(), e.getMessage());
        } catch (RuntimeException e) {
            n.markAttemptFailed(e.getMessage(), props.maxAttempts(), now);
            count(channel, "failed");
            log.warn("Notification {} delivery failed (attempt {}): {}", n.getId(), n.getAttempts(), e.getMessage());
        }
    }

    private void count(String channel, String result) {
        meterRegistry.counter("gymops.notifications", "channel", channel, "result", result).increment();
    }
}
