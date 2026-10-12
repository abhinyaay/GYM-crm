package com.gymops.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.pricing.document.DocumentFacts;
import com.gymops.pricing.document.DocumentFacts.CreditNoteFact;
import com.gymops.pricing.document.DocumentFacts.LineFact;
import com.gymops.pricing.document.DocumentFacts.PaymentFact;
import com.gymops.pricing.document.DocumentFacts.Recipient;
import com.gymops.pricing.document.DocumentFacts.Supplier;
import com.gymops.pricing.document.TaxDocument;
import com.gymops.pricing.document.TaxDocument.Amount;
import com.gymops.pricing.document.TaxDocumentFactory;
import com.gymops.pricing.document.TaxDocumentPdf;
import com.gymops.pricing.gst.GstCalculator.TaxComponent;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaxDocumentTest {

    static final Supplier SUPPLIER = new Supplier("GymOps Fitness Private Limited", "Koregaon Park (PUN1)",
            List.of("1 North Main Road", "Pune, Maharashtra, 411001"), "27AAPFU0939F1ZV", "27", "+91 20 4000 0000",
            "pune@gymops.example");
    static final Recipient MEMBER = new Recipient("Asha Rao", "M000042", List.of("12 Lake View", "Pune, Maharashtra"),
            "asha@example.com", "+91 9000000000");

    static DocumentFacts.Invoice invoice(String placeOfSupply) {
        return new DocumentFacts.Invoice("INV-2026-000042", LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 20), "PAID",
                SUPPLIER, MEMBER, "999723", placeOfSupply,
                List.of(new LineFact("PLAN_FEE", "Monthly membership", new BigDecimal("2500.00")),
                        new LineFact("PRICE_ADJUSTMENT", "Negotiated price adjustment", new BigDecimal("-500.00")),
                        new LineFact("JOINING_FEE", "One-time joining fee", new BigDecimal("500.00"))),
                new BigDecimal("2500.00"), "WELCOME10", new BigDecimal("250.00"), new BigDecimal("0.18"),
                new BigDecimal("405.00"), new BigDecimal("2655.00"), new BigDecimal("2655.00"), new BigDecimal("1000.00"),
                List.of(new PaymentFact(LocalDate.of(2026, 10, 13), "UPI", "asha@okhdfc", "pay_123", "PARTIALLY_REFUNDED",
                        new BigDecimal("2655.00"))),
                List.of(new CreditNoteFact("CN-2026-000007", LocalDate.of(2026, 10, 15), new BigDecimal("1000.00"))));
    }

    @Test
    void intraStateInvoiceHasCgstAndSgstAndConsistentTotals() {
        TaxDocument doc = TaxDocumentFactory.invoice(invoice("27"));
        assertThat(doc.interState()).isFalse();
        assertThat(doc.taxes()).extracting(TaxComponent::name).containsExactly("CGST", "SGST");
        assertThat(doc.taxes()).extracting(TaxComponent::amount).containsExactly(new BigDecimal("202.50"),
                new BigDecimal("202.50"));
        assertThat(doc.lines()).hasSize(3).allSatisfy(l -> assertThat(l.sac()).isEqualTo("999723"));
        assertThat(doc.totals()).extracting(Amount::label).containsExactly("Subtotal", "Discount (WELCOME10)",
                "Taxable value", "CGST @ 9%", "SGST @ 9%", "Invoice total");
        assertThat(doc.totals().get(2).value()).isEqualByComparingTo("2250.00");
        assertThat(doc.totalInWords()).isEqualTo("Indian Rupees Two Thousand Six Hundred Fifty Five Only");
        assertThat(doc.payments()).hasSize(2);
        assertThat(doc.payments().get(1).amount()).isEqualByComparingTo("-1000.00");
        assertThat(doc.placeOfSupply()).isEqualTo("Maharashtra (27)");
    }

    @Test
    void interStateInvoiceHasIgst() {
        TaxDocument doc = TaxDocumentFactory.invoice(invoice("29"));
        assertThat(doc.interState()).isTrue();
        assertThat(doc.taxes()).singleElement().extracting(TaxComponent::name).isEqualTo("IGST");
        assertThat(doc.totals()).extracting(Amount::label).contains("IGST @ 18%");
    }

    @Test
    void invoicePdfContainsTheStatutoryParticulars() throws Exception {
        byte[] pdf = TaxDocumentPdf.render(TaxDocumentFactory.invoice(invoice("27")));
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text).contains("TAX INVOICE", "GymOps Fitness Private Limited", "GSTIN: 27AAPFU0939F1ZV",
                "INV-2026-000042", "13 Oct 2026", "Asha Rao", "M000042", "Maharashtra (27)", "999723",
                "Negotiated price adjustment", "-500.00", "Taxable value", "2,250.00", "CGST @ 9%", "SGST @ 9%", "202.50",
                "2,655.00", "Indian Rupees Two Thousand Six Hundred Fifty Five Only", "CN-2026-000007", "pay_123");
        assertThat(text).doesNotContain("IGST");
    }

    @Test
    void creditNoteReferencesTheOriginalInvoiceAndReversesTax() throws Exception {
        DocumentFacts.CreditNote facts = new DocumentFacts.CreditNote("CN-2026-000007", LocalDate.of(2026, 10, 15),
                "Cancelled within cooling-off", new BigDecimal("1180.00"), "ORIGINAL_METHOD", "rfnd_9", "INV-2026-000042",
                LocalDate.of(2026, 10, 13), SUPPLIER, MEMBER, "999723", "29", new BigDecimal("0.18"));
        TaxDocument doc = TaxDocumentFactory.creditNote(facts);
        assertThat(doc.kind()).isEqualTo(TaxDocument.Kind.CREDIT_NOTE);
        assertThat(doc.taxes()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("IGST");
            assertThat(t.amount()).isEqualByComparingTo("180.00");
        });
        assertThat(doc.totals().get(0).value()).isEqualByComparingTo("1000.00");
        String text = text(TaxDocumentPdf.render(doc));
        assertThat(text).contains("CREDIT NOTE", "CN-2026-000007", "Original invoice", "INV-2026-000042", "IGST @ 18%",
                "1,180.00", "Indian Rupees One Thousand One Hundred Eighty Only", "Cancelled within cooling-off");
    }

    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }
}
