package com.gymops.payment;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.config.AppProperties;
import com.gymops.member.Member;
import com.gymops.payment.dto.InvoiceResponse;
import com.gymops.pricing.PricingProperties;
import com.gymops.pricing.gst.GstSnapshot;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.subscription.Subscription;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository repository;
    private final DiscountService discountService;
    private final AccessPolicy access;
    private final AuditService audit;
    private final AppProperties props;
    private final PricingProperties pricing;
    private final Clock clock;

    /** Issues an invoice for a subscription. Joins the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Invoice issueForSubscription(Subscription subscription, List<PricingCalculator.Line> lines, String discountCode) {
        LocalDate today = LocalDate.now(clock);
        var discount = discountService.redeem(discountCode, today);
        var quote = PricingCalculator.quote(lines, discount, props.billing().taxRate());
        Member member = subscription.getMember();
        Invoice invoice = new Invoice(nextInvoiceNumber(), member, subscription, subscription.getBranch(),
                subscription.getPlan().getCurrency(), quote, discount.orElse(null),
                today.plusDays(props.billing().invoiceDueDays()), Instant.now(clock));
        GstSnapshot gst = GstSnapshot.of(subscription.getBranch(), member);
        invoice.recordTaxRegistration(gst.gstin(), gst.supplierStateCode(), gst.placeOfSupplyCode(), pricing.sacCode());
        repository.save(invoice);
        audit.record(AuditEvent.of("INVOICE", invoice.getId(), "ISSUED").member(member.getId())
                .branch(subscription.getBranch().getId())
                .with("invoiceNumber", invoice.getInvoiceNumber()).with("total", invoice.getTotalAmount())
                .with("subscriptionId", subscription.getId()));
        return invoice;
    }

    /** Settles a zero-amount invoice (e.g. 100% discount) without a payment record. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleZeroAmount(Invoice invoice) {
        invoice.markPaid(invoice.getTotalAmount(), Instant.now(clock));
        audit.record(AuditEvent.of("INVOICE", invoice.getId(), "SETTLED_ZERO_AMOUNT").member(invoice.getMember().getId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void voidInvoice(Invoice invoice, String reason) {
        invoice.voidInvoice(Instant.now(clock));
        if (invoice.getDiscount() != null) {
            discountService.release(invoice.getDiscount());
        }
        audit.record(AuditEvent.of("INVOICE", invoice.getId(), "VOIDED").member(invoice.getMember().getId())
                .with("reason", reason));
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(Long id) {
        Invoice invoice = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Invoice", id));
        access.assertMemberAccess(invoice.getMember().getId(), invoice.getBranch().getId());
        return InvoiceResponse.from(invoice, LocalDate.now(clock));
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> search(Long memberId, Long branchId, InvoiceStatus status,
                                                boolean overdueOnly, LocalDate from, LocalDate to, Pageable pageable) {
        UserPrincipal caller = CurrentUser.get();
        Long effectiveMemberId = caller.isMemberOnly() ? caller.memberId() : memberId;
        BranchScope scope = caller.isMemberOnly() ? BranchScope.ALL : access.scope(branchId);
        LocalDate today = LocalDate.now(clock);
        Specification<Invoice> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (effectiveMemberId != null) {
                p.add(cb.equal(root.get("member").get("id"), effectiveMemberId));
            }
            if (!scope.all()) {
                p.add(root.get("branch").get("id").in(scope.ids()));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (overdueOnly) {
                p.add(cb.equal(root.get("status"), InvoiceStatus.ISSUED));
                p.add(cb.lessThan(root.get("dueDate"), today));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("issuedAt"), from.atStartOfDay(clock.getZone()).toInstant()));
            }
            if (to != null) {
                p.add(cb.lessThan(root.get("issuedAt"), to.plusDays(1).atStartOfDay(clock.getZone()).toInstant()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return PageResponse.of(repository.findAll(spec, pageable), i -> InvoiceResponse.from(i, today));
    }

    private String nextInvoiceNumber() {
        return "INV-%d-%06d".formatted(Year.now(clock).getValue(), repository.nextInvoiceSequence());
    }
}
