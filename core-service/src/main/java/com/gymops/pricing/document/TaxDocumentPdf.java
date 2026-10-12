package com.gymops.pricing.document;

import com.gymops.pricing.document.TaxDocument.Amount;
import com.gymops.pricing.document.TaxDocument.Field;
import com.gymops.pricing.document.TaxDocument.Line;
import com.gymops.pricing.document.TaxDocument.Party;
import com.gymops.pricing.document.TaxDocument.PaymentRow;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;

/**
 * Renders a {@link TaxDocument} as an A4 PDF with OpenPDF. Layout only: every number and label comes
 * from the document. Uses the built-in Helvetica (WinAnsi) font, so amounts are written "INR" rather
 * than with the rupee sign.
 */
public final class TaxDocumentPdf {

    private static final Color INK = new Color(0x1f, 0x29, 0x37);
    private static final Color MUTED = new Color(0x6b, 0x72, 0x80);
    private static final Color RULE = new Color(0xd1, 0xd5, 0xdb);
    private static final Color SHADE = new Color(0xf3, 0xf4, 0xf6);
    private static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD, INK);
    private static final Font NAME = new Font(Font.HELVETICA, 12, Font.BOLD, INK);
    private static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    private static final Font BOLD = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    private static final Font LABEL = new Font(Font.HELVETICA, 8, Font.BOLD, MUTED);

    private TaxDocumentPdf() {
    }

    public static byte[] render(TaxDocument doc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 36, 36, 36, 42);
        try {
            PdfWriter.getInstance(pdf, out);
            pdf.addTitle(doc.title() + " " + doc.number());
            pdf.addCreator("GymOps");
            pdf.open();
            pdf.add(header(doc));
            pdf.add(spacer(8));
            pdf.add(parties(doc));
            pdf.add(spacer(10));
            pdf.add(lines(doc));
            pdf.add(totals(doc));
            Paragraph words = new Paragraph(new Phrase("Amount in words: ", LABEL));
            words.add(new Chunk(doc.totalInWords(), BOLD));
            words.setSpacingBefore(6);
            pdf.add(words);
            if (!doc.payments().isEmpty()) {
                pdf.add(spacer(10));
                Paragraph heading = new Paragraph(doc.paymentsHeading(), BOLD);
                heading.setSpacingAfter(4);
                pdf.add(heading);
                pdf.add(payments(doc));
            }
            pdf.add(spacer(10));
            for (String note : doc.notes()) {
                pdf.add(new Paragraph(note, SMALL));
            }
            Paragraph footer = new Paragraph("This is a computer-generated " + doc.title().toLowerCase(java.util.Locale.ROOT)
                    + " and does not require a signature.", SMALL);
            footer.setSpacingBefore(14);
            footer.setAlignment(Element.ALIGN_CENTER);
            pdf.add(footer);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render " + doc.number(), e);
        } finally {
            pdf.close();
        }
        return out.toByteArray();
    }

    private static PdfPTable header(TaxDocument doc) {
        PdfPTable t = table(new float[] {3, 2});
        PdfPCell left = cell(Rectangle.NO_BORDER);
        left.addElement(new Paragraph(doc.supplier().name(), NAME));
        doc.supplier().lines().forEach(l -> left.addElement(new Paragraph(l, BODY)));
        t.addCell(left);

        PdfPCell right = cell(Rectangle.NO_BORDER);
        Paragraph title = new Paragraph(doc.title(), TITLE);
        title.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(title);
        if (doc.kind() == TaxDocument.Kind.TAX_INVOICE) {
            Paragraph copy = new Paragraph("Original for recipient", SMALL);
            copy.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(copy);
        }
        PdfPTable fields = table(new float[] {1.2f, 1.6f});
        for (Field f : doc.header()) {
            PdfPCell k = new PdfPCell(new Phrase(f.label(), LABEL));
            k.setBorder(Rectangle.NO_BORDER);
            k.setHorizontalAlignment(Element.ALIGN_RIGHT);
            fields.addCell(k);
            PdfPCell v = new PdfPCell(new Phrase(f.value(), BOLD));
            v.setBorder(Rectangle.NO_BORDER);
            v.setHorizontalAlignment(Element.ALIGN_RIGHT);
            fields.addCell(v);
        }
        right.addElement(fields);
        t.addCell(right);
        return t;
    }

    private static PdfPTable parties(TaxDocument doc) {
        PdfPTable t = table(new float[] {1, 1});
        t.addCell(partyCell(doc.recipient()));
        PdfPCell supply = boxed();
        supply.addElement(new Paragraph("Supply", LABEL));
        supply.addElement(new Paragraph("Place of supply: " + doc.placeOfSupply(), BODY));
        supply.addElement(new Paragraph(doc.interState() ? "Inter-state supply: IGST" : "Intra-state supply: CGST + SGST",
                BODY));
        t.addCell(supply);
        return t;
    }

    private static PdfPCell partyCell(Party p) {
        PdfPCell c = boxed();
        c.addElement(new Paragraph(p.heading(), LABEL));
        c.addElement(new Paragraph(p.name(), BOLD));
        p.lines().forEach(l -> c.addElement(new Paragraph(l, BODY)));
        return c;
    }

    private static PdfPTable lines(TaxDocument doc) {
        PdfPTable t = table(new float[] {0.5f, 6, 1.2f, 2});
        t.setHeaderRows(1);
        for (String h : new String[] {"#", "Description", "SAC", "Amount (INR)"}) {
            PdfPCell c = new PdfPCell(new Phrase(h, LABEL));
            c.setBackgroundColor(SHADE);
            c.setBorderColor(RULE);
            c.setPadding(5);
            c.setHorizontalAlignment(h.startsWith("Amount") ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            t.addCell(c);
        }
        for (Line l : doc.lines()) {
            t.addCell(text(String.valueOf(l.no()), Element.ALIGN_LEFT));
            t.addCell(text(l.description(), Element.ALIGN_LEFT));
            t.addCell(text(l.sac(), Element.ALIGN_LEFT));
            t.addCell(text(TaxDocumentFactory.money(l.amount()), Element.ALIGN_RIGHT));
        }
        return t;
    }

    private static PdfPTable totals(TaxDocument doc) {
        PdfPTable t = table(new float[] {6.5f, 2.2f, 2});
        for (Amount a : doc.totals()) {
            PdfPCell gap = new PdfPCell(new Phrase(""));
            gap.setBorder(Rectangle.NO_BORDER);
            t.addCell(gap);
            Font f = a.strong() ? BOLD : BODY;
            PdfPCell k = new PdfPCell(new Phrase(a.label(), f));
            k.setBorder(Rectangle.BOTTOM);
            k.setBorderColor(RULE);
            k.setPadding(4);
            t.addCell(k);
            PdfPCell v = new PdfPCell(new Phrase(TaxDocumentFactory.money(a.value()), f));
            v.setBorder(Rectangle.BOTTOM);
            v.setBorderColor(RULE);
            v.setPadding(4);
            v.setHorizontalAlignment(Element.ALIGN_RIGHT);
            if (a.strong()) {
                k.setBackgroundColor(SHADE);
                v.setBackgroundColor(SHADE);
            }
            t.addCell(v);
        }
        return t;
    }

    private static PdfPTable payments(TaxDocument doc) {
        PdfPTable t = table(new float[] {1.4f, 3.6f, 2.6f, 1.4f, 1.6f});
        for (String h : new String[] {"Date", "Description", "Reference", "Status", "Amount (INR)"}) {
            PdfPCell c = new PdfPCell(new Phrase(h, LABEL));
            c.setBackgroundColor(SHADE);
            c.setBorderColor(RULE);
            c.setPadding(4);
            t.addCell(c);
        }
        for (PaymentRow p : doc.payments()) {
            t.addCell(text(p.date(), Element.ALIGN_LEFT));
            t.addCell(text(p.description(), Element.ALIGN_LEFT));
            t.addCell(text(p.reference(), Element.ALIGN_LEFT));
            t.addCell(text(p.status(), Element.ALIGN_LEFT));
            t.addCell(text(TaxDocumentFactory.money(p.amount()), Element.ALIGN_RIGHT));
        }
        return t;
    }

    // ------------------------------------------------------------------ primitives

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        return t;
    }

    private static PdfPCell cell(int border) {
        PdfPCell c = new PdfPCell();
        c.setBorder(border);
        c.setPadding(0);
        return c;
    }

    private static PdfPCell boxed() {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(RULE);
        c.setPadding(6);
        return c;
    }

    private static PdfPCell text(String value, int align) {
        PdfPCell c = new PdfPCell(new Phrase(value == null ? "" : value, BODY));
        c.setBorderColor(RULE);
        c.setPadding(4);
        c.setHorizontalAlignment(align);
        return c;
    }

    private static Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ", SMALL);
        p.setLeading(height);
        return p;
    }
}
