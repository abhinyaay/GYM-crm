package com.gymops.payment;

/**
 * Callback invoked inside the payment transaction once an invoice is settled. Implemented by the
 * subscription module (dependency inversion keeps billing independent of subscription rules).
 */
public interface PaidInvoiceHandler {

    void onInvoicePaid(Invoice invoice);
}
