package com.gymops.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.gymops.payment.PaymentMethod;
import com.gymops.payment.gateway.PaymentGateway.PaymentState;
import com.gymops.payment.gateway.PaymentGateway.ProviderPayment;
import com.gymops.payment.gateway.PaymentGateway.WebhookEvent;

/** Maps Razorpay's payment / webhook JSON (also used by the simulator) to normalised values. */
final class RazorpayJson {

    private RazorpayJson() {
    }

    static ProviderPayment payment(JsonNode p) {
        String status = text(p, "status");
        PaymentState state = switch (status == null ? "" : status) {
            case "captured", "refunded" -> PaymentState.CAPTURED;
            case "failed" -> PaymentState.FAILED;
            default -> PaymentState.PENDING;          // created / authorized
        };
        PaymentMethod method = method(text(p, "method"));
        JsonNode card = p.path("card");
        String instrument = switch (method) {
            case CARD, EMI -> card.isMissingNode() || card.isNull() ? label(method)
                    : text(card, "network") + " •••• " + text(card, "last4")
                    + (method == PaymentMethod.EMI ? " (EMI)" : "");
            case UPI -> "UPI " + maskVpa(text(p, "vpa"));
            case NET_BANKING -> "Net banking " + nullToEmpty(text(p, "bank"));
            case WALLET -> "Wallet " + nullToEmpty(text(p, "wallet"));
            default -> label(method);
        };
        return new ProviderPayment(text(p, "id"), text(p, "order_id"), state, method, p.path("amount").asLong(),
                instrument.trim(), text(p, "error_description"), text(p, "customer_id"), text(p, "token_id"),
                card.isMissingNode() ? null : text(card, "network"), card.isMissingNode() ? null : text(card, "last4"),
                null, null, method == PaymentMethod.UPI ? maskVpa(text(p, "vpa")) : null);
    }

    static WebhookEvent event(JsonNode root) {
        String type = text(root, "event");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        JsonNode token = root.path("payload").path("token").path("entity");
        return new WebhookEvent(type,
                payment.isMissingNode() ? null : payment(payment),
                token.isMissingNode() ? null : text(token, "id"),
                token.isMissingNode() ? null : text(token.path("recurring_details"), "status"));
    }

    static PaymentMethod method(String razorpayMethod) {
        return switch (razorpayMethod == null ? "" : razorpayMethod) {
            case "upi" -> PaymentMethod.UPI;
            case "netbanking" -> PaymentMethod.NET_BANKING;
            case "wallet" -> PaymentMethod.WALLET;
            case "emi", "cardless_emi" -> PaymentMethod.EMI;
            default -> PaymentMethod.CARD;
        };
    }

    static String razorpayMethod(PaymentMethod method) {
        return switch (method) {
            case UPI -> "upi";
            case NET_BANKING -> "netbanking";
            case WALLET -> "wallet";
            case EMI -> "emi";
            default -> "card";
        };
    }

    /** name@okhdfc -> na***@okhdfc */
    static String maskVpa(String vpa) {
        if (vpa == null || !vpa.contains("@")) {
            return vpa;
        }
        int at = vpa.indexOf('@');
        String user = vpa.substring(0, at);
        return (user.length() <= 2 ? user : user.substring(0, 2)) + "***" + vpa.substring(at);
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String label(PaymentMethod m) {
        return m.name().charAt(0) + m.name().substring(1).toLowerCase().replace('_', ' ');
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.toUpperCase();
    }
}
