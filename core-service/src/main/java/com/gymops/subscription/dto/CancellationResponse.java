package com.gymops.subscription.dto;

import com.gymops.payment.dto.RefundResponse;
import java.math.BigDecimal;

public record CancellationResponse(SubscriptionResponse subscription, BigDecimal refundAmount, RefundResponse refund) {
}
