package com.gymops.pricing.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Plain inputs for {@link TaxDocumentFactory}, mapped from the invoice / refund entities by the service. */
public final class DocumentFacts {

    private DocumentFacts() {
    }

    /** The registered supplier: legal entity plus the branch that supplied the service. */
    public record Supplier(String legalName, String branchName, List<String> address, String gstin, String stateCode,
                           String phone, String email) {
    }

    public record Recipient(String name, String memberCode, List<String> address, String email, String phone) {
    }

    public record LineFact(String type, String description, BigDecimal amount) {
    }

    public record PaymentFact(LocalDate date, String method, String instrument, String reference, String status,
                              BigDecimal amount) {
    }

    public record CreditNoteFact(String number, LocalDate date, BigDecimal amount) {
    }

    public record Invoice(String number, LocalDate issueDate, LocalDate dueDate, String status, Supplier supplier,
                          Recipient recipient, String sac, String placeOfSupplyCode, List<LineFact> lines,
                          BigDecimal subtotal, String discountCode, BigDecimal discountAmount, BigDecimal taxRate,
                          BigDecimal taxAmount, BigDecimal total, BigDecimal amountPaid, BigDecimal amountRefunded,
                          List<PaymentFact> payments, List<CreditNoteFact> creditNotes) {
    }

    public record CreditNote(String number, LocalDate date, String reason, BigDecimal amount, String destination,
                             String refundReference, String invoiceNumber, LocalDate invoiceDate, Supplier supplier,
                             Recipient recipient, String sac, String placeOfSupplyCode, BigDecimal taxRate) {
    }
}
