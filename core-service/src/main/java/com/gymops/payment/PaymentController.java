package com.gymops.payment;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.payment.dto.DiscountRequest;
import com.gymops.payment.dto.DiscountResponse;
import com.gymops.payment.dto.InvoiceResponse;
import com.gymops.payment.dto.PaymentRequest;
import com.gymops.payment.dto.PaymentResponse;
import com.gymops.payment.dto.RefundRequest;
import com.gymops.payment.dto.RefundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments, invoices & discounts")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final String STAFF = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')";
    private static final Set<String> PAYMENT_SORT = Set.of("processedAt", "amount");
    private static final Set<String> INVOICE_SORT = Set.of("issuedAt", "dueDate", "totalAmount");

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final InvoiceService invoiceService;
    private final DiscountService discountService;

    @Operation(summary = "Pay an invoice (idempotent)",
            description = "Requires an Idempotency-Key header. Retrying with the same key returns the original "
                    + "payment with header Idempotent-Replayed: true. 201 = succeeded, 402 = declined (the failed "
                    + "attempt is recorded; retry with a NEW key).")
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')")
    public ResponseEntity<PaymentResponse> pay(
            @Parameter(description = "Client-generated unique key, e.g. a UUID") @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PaymentRequest request) {
        PaymentService.PaymentOutcome outcome = paymentService.pay(request, key);
        PaymentResponse body = outcome.payment();
        HttpStatus status = body.status() == PaymentStatus.FAILED ? HttpStatus.PAYMENT_REQUIRED
                : outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .location(URI.create("/api/v1/payments/" + body.id()))
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(body);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable Long id) {
        return paymentService.get(id);
    }

    @Operation(summary = "Payment history (members see only their own)")
    @GetMapping
    public PageResponse<PaymentResponse> search(
            @RequestParam(required = false) Long memberId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) PaymentStatus status, @RequestParam(required = false) PaymentMethod method,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return paymentService.search(memberId, branchId, status, method, from, to,
                PageRequests.of(page, size, sort, PAYMENT_SORT, Sort.by(Sort.Direction.DESC, "processedAt")));
    }

    @Operation(summary = "Refund a payment fully or partially (idempotent with Idempotency-Key)")
    @PostMapping("/{id}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public RefundResponse refund(@PathVariable Long id,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                 @Valid @RequestBody RefundRequest request) {
        return refundService.refund(id, request.amount(), request.reason(), key, request.destination());
    }

    @GetMapping("/{id}/refunds")
    public List<RefundResponse> listRefunds(@PathVariable Long id) {
        return refundService.listForPayment(id);
    }

    // ------------------------------------------------------------------ invoices

    @Operation(summary = "Invoices; use status=ISSUED for outstanding and overdue=true for overdue invoices")
    @GetMapping("/invoices")
    public PageResponse<InvoiceResponse> invoices(
            @RequestParam(required = false) Long memberId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) InvoiceStatus status, @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return invoiceService.search(memberId, branchId, status, overdue, from, to,
                PageRequests.of(page, size, sort, INVOICE_SORT, Sort.by(Sort.Direction.DESC, "issuedAt")));
    }

    @Operation(summary = "Outstanding (unpaid) invoices")
    @GetMapping("/invoices/outstanding")
    @PreAuthorize(STAFF)
    public PageResponse<InvoiceResponse> outstanding(@RequestParam(required = false) Long branchId,
                                                     @RequestParam(defaultValue = "false") boolean overdue,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return invoiceService.search(null, branchId, InvoiceStatus.ISSUED, overdue, null, null,
                PageRequests.of(page, size, null, INVOICE_SORT, Sort.by("dueDate")));
    }

    @GetMapping("/invoices/{id}")
    public InvoiceResponse invoice(@PathVariable Long id) {
        return invoiceService.get(id);
    }

    // ------------------------------------------------------------------ discounts

    @GetMapping("/discounts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public PageResponse<DiscountResponse> discounts(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return discountService.list(PageRequests.of(page, size, null, Set.of(), Sort.by("code")));
    }

    @PostMapping("/discounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public DiscountResponse createDiscount(@Valid @RequestBody DiscountRequest request) {
        return discountService.create(request);
    }

    @PatchMapping("/discounts/{id}/deactivate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public DiscountResponse deactivateDiscount(@PathVariable Long id) {
        return discountService.deactivate(id);
    }
}
