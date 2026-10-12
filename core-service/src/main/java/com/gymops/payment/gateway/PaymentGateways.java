package com.gymops.payment.gateway;

import com.gymops.payment.PaymentProvider;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * All configured gateways, keyed by provider. New checkouts use the {@link #active()} one; refunds,
 * token charges and webhooks are routed to the provider that originally handled the payment, so a
 * switch from the simulator to Razorpay never strands existing payments or saved methods.
 */
public class PaymentGateways {

    private final Map<PaymentProvider, PaymentGateway> byProvider = new EnumMap<>(PaymentProvider.class);
    private final PaymentProvider active;

    public PaymentGateways(List<PaymentGateway> gateways, PaymentProvider active) {
        gateways.forEach(g -> byProvider.put(g.provider(), g));
        if (!byProvider.containsKey(active)) {
            throw new IllegalStateException("app.payments.gateway=" + active + " but that gateway is not configured"
                    + (active == PaymentProvider.RAZORPAY ? " (set RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET / RAZORPAY_WEBHOOK_SECRET)" : ""));
        }
        this.active = active;
    }

    public PaymentGateway active() {
        return byProvider.get(active);
    }

    public PaymentProvider activeProvider() {
        return active;
    }

    /** Gateway for a provider, or empty for OFFLINE / INTERNAL (and unconfigured providers). */
    public java.util.Optional<PaymentGateway> find(PaymentProvider provider) {
        return java.util.Optional.ofNullable(byProvider.get(provider));
    }

    public PaymentGateway get(PaymentProvider provider) {
        return find(provider).orElseThrow(() -> new IllegalStateException("Payment provider " + provider + " is not configured"));
    }

    public SimulatedPaymentGateway simulated() {
        return (SimulatedPaymentGateway) get(PaymentProvider.SIMULATED);
    }
}
