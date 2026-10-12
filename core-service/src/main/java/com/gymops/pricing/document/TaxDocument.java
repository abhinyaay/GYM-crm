package com.gymops.pricing.document;

import com.gymops.pricing.gst.GstCalculator.TaxComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything printed on a GST tax invoice or credit note, already computed. Built by
 * {@link TaxDocumentFactory} (pure) and rendered by {@link TaxDocumentPdf}; tests assert on either.
 */
public record TaxDocument(
        Kind kind, String title, String number, LocalDate date, List<Field> header,
        Party supplier, Party recipient, String placeOfSupply, boolean interState,
        List<Line> lines, List<Amount> totals, List<TaxComponent> taxes, BigDecimal total, String totalInWords,
        String paymentsHeading, List<PaymentRow> payments, List<String> notes) {

    public enum Kind {
        TAX_INVOICE,
        CREDIT_NOTE
    }

    public record Field(String label, String value) {
    }

    public record Party(String heading, String name, List<String> lines) {
    }

    public record Line(int no, String description, String sac, BigDecimal amount) {
    }

    public record Amount(String label, BigDecimal value, boolean strong) {
    }

    public record PaymentRow(String date, String description, String reference, String status, BigDecimal amount) {
    }
}
