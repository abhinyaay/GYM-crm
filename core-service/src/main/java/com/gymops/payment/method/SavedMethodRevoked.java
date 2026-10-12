package com.gymops.payment.method;

/** Published (synchronously, inside the transaction) when a saved method can no longer be debited. */
public record SavedMethodRevoked(Long paymentMethodId, Long memberId, String reason) {
}
