package com.gymops.payment.checkout;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEventLog, Long> {

    Optional<WebhookEventLog> findByProviderAndEventId(String provider, String eventId);
}
