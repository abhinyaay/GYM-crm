package com.gymops.pricing.document;

import com.gymops.branch.GymBranch;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.payment.Invoice;
import com.gymops.payment.InvoiceRepository;
import com.gymops.payment.Payment;
import com.gymops.payment.Refund;
import com.gymops.pricing.PricingProperties;
import com.gymops.pricing.document.DocumentFacts.CreditNoteFact;
import com.gymops.pricing.document.DocumentFacts.LineFact;
import com.gymops.pricing.document.DocumentFacts.PaymentFact;
import com.gymops.pricing.document.DocumentFacts.Recipient;
import com.gymops.pricing.document.DocumentFacts.Supplier;
import com.gymops.pricing.gst.GstSnapshot;
import com.gymops.security.AccessPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GST tax invoices and credit notes as PDF. Members may download their own documents, staff those of
 * the branches they can access (same rule as reading the invoice).
 */
@Service
@RequiredArgsConstructor
public class BillingDocumentService {

    public record Pdf(String filename, byte[] content) {
    }

    private final InvoiceRepository invoices;
    private final BillingDocumentQueries queries;
    private final AccessPolicy access;
    private final PricingProperties props;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Pdf invoicePdf(Long invoiceId) {
        Invoice invoice = invoices.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId));
        access.assertMemberAccess(invoice.getMember().getId(), invoice.getBranch().getId());
        TaxDocument doc = TaxDocumentFactory.invoice(invoiceFacts(invoice));
        return new Pdf(invoice.getInvoiceNumber() + ".pdf", TaxDocumentPdf.render(doc));
    }

    @Transactional(readOnly = true)
    public Pdf creditNotePdf(Long refundId) {
        Refund refund = queries.refund(refundId);
        if (refund == null) {
            throw new ResourceNotFoundException("Refund", refundId);
        }
        Invoice invoice = refund.getPayment().getInvoice();
        access.assertMemberAccess(invoice.getMember().getId(), invoice.getBranch().getId());
        TaxDocument doc = TaxDocumentFactory.creditNote(creditNoteFacts(refund));
        String number = refund.getCreditNoteNumber() == null ? "CN-" + refund.getId() : refund.getCreditNoteNumber();
        return new Pdf(number + ".pdf", TaxDocumentPdf.render(doc));
    }

    // ------------------------------------------------------------------ mapping

    DocumentFacts.Invoice invoiceFacts(Invoice i) {
        GstView gst = gst(i);
        List<LineFact> lines = i.getLines().stream()
                .map(l -> new LineFact(l.getLineType().name(), l.getDescription(), l.getAmount())).toList();
        List<PaymentFact> payments = new ArrayList<>();
        for (Payment p : queries.paymentsOf(i.getId())) {
            payments.add(new PaymentFact(date(p.getProcessedAt()), p.getMethod().name(), p.getInstrument(),
                    p.getGatewayReference(), p.getStatus().name(), p.getAmount()));
        }
        List<CreditNoteFact> notes = queries.refundsOf(i.getId()).stream()
                .map(r -> new CreditNoteFact(r.getCreditNoteNumber(), date(r.getProcessedAt()), r.getAmount())).toList();
        return new DocumentFacts.Invoice(i.getInvoiceNumber(), date(i.getIssuedAt()), i.getDueDate(), i.getStatus().name(),
                gst.supplier(), recipient(i.getMember()), gst.sac(), gst.placeOfSupply(), lines, i.getSubtotal(),
                i.getDiscount() == null ? null : i.getDiscount().getCode(), i.getDiscountAmount(), i.getTaxRate(),
                i.getTaxAmount(), i.getTotalAmount(), i.getAmountPaid(), i.getAmountRefunded(), payments, notes);
    }

    DocumentFacts.CreditNote creditNoteFacts(Refund r) {
        Invoice i = r.getPayment().getInvoice();
        GstView gst = gst(i);
        String number = r.getCreditNoteNumber() == null ? "CN-" + r.getId() : r.getCreditNoteNumber();
        return new DocumentFacts.CreditNote(number, date(r.getProcessedAt()), r.getReason(), r.getAmount(),
                r.getDestination().name(), r.getGatewayReference(), i.getInvoiceNumber(), date(i.getIssuedAt()),
                gst.supplier(), recipient(i.getMember()), gst.sac(), gst.placeOfSupply(), i.getTaxRate());
    }

    private record GstView(Supplier supplier, String placeOfSupply, String sac) {
    }

    /** Snapshot taken at issue time; invoices issued before it existed use the branch's current registration. */
    private GstView gst(Invoice i) {
        GymBranch b = i.getBranch();
        GstSnapshot current = GstSnapshot.of(b, i.getMember());
        boolean snapshot = i.getSupplierStateCode() != null || i.getSupplierGstin() != null;
        String gstin = snapshot ? i.getSupplierGstin() : current.gstin();
        String supplierState = snapshot ? i.getSupplierStateCode() : current.supplierStateCode();
        String place = i.getPlaceOfSupplyCode() != null ? i.getPlaceOfSupplyCode() : current.placeOfSupplyCode();
        List<String> address = new ArrayList<>();
        address.add(b.getAddressLine1());
        if (b.getAddressLine2() != null && !b.getAddressLine2().isBlank()) {
            address.add(b.getAddressLine2());
        }
        address.add(join(b.getCity(), b.getState(), b.getPostalCode()));
        Supplier supplier = new Supplier(props.legalBusinessName(), b.getName() + " (" + b.getCode() + ")", address, gstin,
                supplierState, b.getPhone(), b.getEmail());
        return new GstView(supplier, place, i.getSacCode() == null ? props.sacCode() : i.getSacCode());
    }

    private static Recipient recipient(Member m) {
        List<String> address = new ArrayList<>();
        if (m.getAddressLine1() != null && !m.getAddressLine1().isBlank()) {
            address.add(m.getAddressLine1());
        }
        String cityLine = join(m.getCity(), m.getState(), m.getPostalCode());
        if (!cityLine.isBlank()) {
            address.add(cityLine);
        }
        return new Recipient(m.fullName(), m.getMemberCode(), address, m.getEmail(), m.getPhone());
    }

    private static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                out.add(p.trim());
            }
        }
        return String.join(", ", out);
    }

    private LocalDate date(java.time.Instant instant) {
        return LocalDate.ofInstant(instant, clock.getZone());
    }
}
