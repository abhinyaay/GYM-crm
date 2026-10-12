package com.gymops.pricing.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments, invoices & discounts")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class BillingDocumentController {

    private static final String CALLERS = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')";

    private final BillingDocumentService service;

    @Operation(summary = "GST tax invoice as PDF (members: own invoices; staff: their branches)")
    @GetMapping("/invoices/{id}/pdf")
    @PreAuthorize(CALLERS)
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long id) {
        return pdf(service.invoicePdf(id));
    }

    @Operation(summary = "GST credit note for a refund as PDF, referencing the original invoice")
    @GetMapping("/refunds/{id}/credit-note.pdf")
    @PreAuthorize(CALLERS)
    public ResponseEntity<byte[]> creditNotePdf(@PathVariable Long id) {
        return pdf(service.creditNotePdf(id));
    }

    private static ResponseEntity<byte[]> pdf(BillingDocumentService.Pdf pdf) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(pdf.filename()).build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf.content());
    }
}
