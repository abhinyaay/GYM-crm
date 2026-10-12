package com.gymops.payment.checkout;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentIntent> findByProviderOrderId(String providerOrderId);
}
