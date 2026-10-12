package com.gymops.pricing.document;

import com.gymops.common.util.Money;
import com.gymops.pricing.document.DocumentFacts.CreditNote;
import com.gymops.pricing.document.DocumentFacts.Invoice;
import com.gymops.pricing.document.DocumentFacts.Recipient;
import com.gymops.pricing.document.DocumentFacts.Supplier;
import com.gymops.pricing.document.TaxDocument.Amount;
import com.gymops.pricing.document.TaxDocument.Field;
import com.gymops.pricing.document.TaxDocument.Kind;
import com.gymops.pricing.document.TaxDocument.Line;
import com.gymops.pricing.document.TaxDocument.Party;
import com.gymops.pricing.document.TaxDocument.PaymentRow;
import com.gymops.pricing.gst.AmountInWords;
import com.gymops.pricing.gst.GstCalculator;
import com.gymops.pricing.gst.GstCalculator.TaxComponent;
import com.gymops.pricing.gst.IndianStates;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds tax invoices and credit notes from plain facts (no I/O, no PDF). */
public final class TaxDocumentFactory {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private TaxDocumentFactory() {
    }

    public static TaxDocument invoice(Invoice f) {
        boolean inter = GstCalculator.isInterState(f.supplier().stateCode(), f.placeOfSupplyCode());
        List<Line> lines = new ArrayList<>();
        int no = 1;
        for (DocumentFacts.LineFact l : f.lines()) {
            lines.add(new Line(no++, l.description(), f.sac(), l.amount()));
        }
        BigDecimal taxable = f.subtotal().subtract(f.discountAmount());
        List<TaxComponent> taxes = GstCalculator.split(f.taxAmount(), f.taxRate(), inter);
        List<Amount> totals = new ArrayList<>();
        totals.add(new Amount("Subtotal", f.subtotal(), false));
        if (f.discountAmount().signum() > 0) {
            totals.add(new Amount("Discount" + (f.discountCode() == null ? "" : " (" + f.discountCode() + ")"),
                    f.discountAmount().negate(), false));
        }
        totals.add(new Amount("Taxable value", taxable, true));
        taxes.forEach(t -> totals.add(new Amount(t.name() + " @ " + t.ratePct().toPlainString() + "%", t.amount(), false)));
        totals.add(new Amount("Invoice total", f.total(), true));

        List<Field> header = new ArrayList<>();
        header.add(new Field("Invoice no.", f.number()));
        header.add(new Field("Invoice date", DATE.format(f.issueDate())));
        header.add(new Field("Due date", DATE.format(f.dueDate())));
        header.add(new Field("Status", f.status()));
        header.add(new Field("Place of supply", IndianStates.label(f.placeOfSupplyCode())));
        header.add(new Field("Reverse charge", "No"));

        List<PaymentRow> payments = new ArrayList<>();
        f.payments().forEach(p -> payments.add(new PaymentRow(DATE.format(p.date()), describe(p.method(), p.instrument()),
                p.reference() == null ? "-" : p.reference(), p.status(), p.amount())));
        f.creditNotes().forEach(c -> payments.add(new PaymentRow(DATE.format(c.date()), "Credit note (refund)", c.number(),
                "ISSUED", c.amount().negate())));

        List<String> notes = new ArrayList<>();
        notes.add("Amount paid: INR " + money(f.amountPaid()) + (f.amountRefunded().signum() > 0
                ? "; refunded: INR " + money(f.amountRefunded()) : "") + "; balance due: INR "
                + money(balance(f)) + ".");
        if (f.supplier().gstin() == null) {
            notes.add("Supplier GSTIN not registered on this branch; tax shown at the applicable GST rate.");
        }
        notes.add("Services: fitness / health club services (SAC " + f.sac() + ").");
        return new TaxDocument(Kind.TAX_INVOICE, "TAX INVOICE", f.number(), f.issueDate(), header,
                supplierParty(f.supplier()), recipientParty("Bill to", f.recipient(), f.placeOfSupplyCode()),
                IndianStates.label(f.placeOfSupplyCode()), inter, lines, totals, taxes, f.total(),
                AmountInWords.rupees(f.total()), "Payments & credit notes", payments, notes);
    }

    public static TaxDocument creditNote(CreditNote f) {
        boolean inter = GstCalculator.isInterState(f.supplier().stateCode(), f.placeOfSupplyCode());
        BigDecimal[] parts = GstCalculator.fromInclusive(f.amount(), f.taxRate());
        BigDecimal taxable = parts[0];
        List<TaxComponent> taxes = GstCalculator.split(parts[1], f.taxRate(), inter);
        List<Line> lines = List.of(new Line(1, "Refund against invoice " + f.invoiceNumber() + ": " + f.reason(), f.sac(),
                taxable));
        List<Amount> totals = new ArrayList<>();
        totals.add(new Amount("Taxable value", taxable, true));
        taxes.forEach(t -> totals.add(new Amount(t.name() + " @ " + t.ratePct().toPlainString() + "%", t.amount(), false)));
        totals.add(new Amount("Credit note total", Money.of(f.amount()), true));

        List<Field> header = List.of(
                new Field("Credit note no.", f.number()),
                new Field("Date", DATE.format(f.date())),
                new Field("Original invoice", f.invoiceNumber()),
                new Field("Invoice date", DATE.format(f.invoiceDate())),
                new Field("Place of supply", IndianStates.label(f.placeOfSupplyCode())),
                new Field("Reverse charge", "No"));
        List<PaymentRow> payments = List.of(new PaymentRow(DATE.format(f.date()),
                "STORE_CREDIT".equals(f.destination()) ? "Refund to store credit" : "Refund to original payment method",
                f.refundReference() == null ? "-" : f.refundReference(), "SUCCEEDED", Money.of(f.amount())));
        List<String> notes = List.of("Issued under section 34 of the CGST Act against tax invoice " + f.invoiceNumber()
                        + " dated " + DATE.format(f.invoiceDate()) + ".",
                "Reason: " + f.reason());
        return new TaxDocument(Kind.CREDIT_NOTE, "CREDIT NOTE", f.number(), f.date(), header,
                supplierParty(f.supplier()), recipientParty("Issued to", f.recipient(), f.placeOfSupplyCode()),
                IndianStates.label(f.placeOfSupplyCode()), inter, lines, totals, taxes, Money.of(f.amount()),
                AmountInWords.rupees(f.amount()), "Refund", payments, notes);
    }

    private static BigDecimal balance(Invoice f) {
        return "VOID".equals(f.status()) ? Money.ZERO : Money.of(f.total().subtract(f.amountPaid()).max(BigDecimal.ZERO));
    }

    private static Party supplierParty(Supplier s) {
        List<String> lines = new ArrayList<>();
        lines.add(s.branchName());
        lines.addAll(s.address());
        lines.add("GSTIN: " + (s.gstin() == null ? "Not registered" : s.gstin()));
        lines.add("State: " + IndianStates.label(s.stateCode()));
        if (s.phone() != null || s.email() != null) {
            lines.add(String.join("  |  ", nonNull(s.phone(), s.email())));
        }
        return new Party("Supplier", s.legalName(), lines);
    }

    private static Party recipientParty(String heading, Recipient r, String placeOfSupplyCode) {
        List<String> lines = new ArrayList<>();
        lines.add("Member " + r.memberCode());
        lines.addAll(r.address());
        lines.add("State: " + IndianStates.label(placeOfSupplyCode));
        lines.add("GSTIN: Unregistered (B2C)");
        if (r.phone() != null || r.email() != null) {
            lines.add(String.join("  |  ", nonNull(r.phone(), r.email())));
        }
        return new Party(heading, r.name(), lines);
    }

    private static List<String> nonNull(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private static String describe(String method, String instrument) {
        return instrument == null || instrument.isBlank() ? method : method + " - " + instrument;
    }

    /** Indian digit grouping: 1,23,456.78 (negative amounts with a leading minus). */
    public static String money(BigDecimal value) {
        BigDecimal v = Money.of(value);
        String sign = v.signum() < 0 ? "-" : "";
        String plain = v.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        String fraction = plain.substring(dot);
        if (whole.length() <= 3) {
            return sign + whole + fraction;
        }
        String last3 = whole.substring(whole.length() - 3);
        StringBuilder rest = new StringBuilder(whole.substring(0, whole.length() - 3));
        for (int i = rest.length() - 2; i > 0; i -= 2) {
            rest.insert(i, ',');
        }
        return sign + rest + "," + last3 + fraction;
    }
}
