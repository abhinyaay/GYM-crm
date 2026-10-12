package com.gymops.pricing;

import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.util.Money;
import com.gymops.payment.InvoiceLineType;
import com.gymops.payment.PaymentProperties;
import com.gymops.payment.PricingCalculator;
import com.gymops.plan.MembershipPlan;
import com.gymops.pricing.PriceResolver.Notice;
import com.gymops.pricing.PriceResolver.PlanPrice;
import com.gymops.pricing.PriceResolver.RenewalRequest;
import com.gymops.pricing.PriceResolver.Resolution;
import com.gymops.pricing.PriceResolver.ScheduledChange;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.subscription.Subscription;
import com.gymops.user.RoleName;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Answers "what does this sale / renewal cost" for the subscription and autopay code. Reads only:
 * it never changes a plan (scheduled changes that are already due are honoured lazily, see
 * {@link PriceResolver}).
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final EnumSet<RoleName> MAY_OVERRIDE = EnumSet.of(RoleName.SUPER_ADMIN, RoleName.GYM_ADMIN,
            RoleName.MANAGER);
    private static final int MIN_REASON_LENGTH = 5;

    private final PlanPriceChangeRepository changes;
    private final PriceChangeNoticeRepository notices;
    private final PricingProperties props;
    private final PaymentProperties paymentProps;
    private final Clock clock;

    // ================================================================== list prices

    public PlanPrice planPrice(MembershipPlan plan) {
        ScheduledChange scheduled = changes.findFirstByPlanIdAndStatus(plan.getId(), PriceChangeStatus.SCHEDULED)
                .map(PricingService::toScheduled).orElse(null);
        return new PlanPrice(plan.getPrice(), plan.getJoiningFee(), scheduled);
    }

    /** The plan's list price for a sale on {@code date} (honours a due scheduled change). */
    public BigDecimal listPrice(MembershipPlan plan, LocalDate date) {
        return planPrice(plan).priceOn(date);
    }

    static ScheduledChange toScheduled(PlanPriceChange c) {
        return new ScheduledChange(c.getId(), c.getPlan().getPrice(), c.getNewPrice(), c.getNewJoiningFee(),
                c.getEffectiveDate(), c.getPolicy(), c.getProtectedUntil());
    }

    // ================================================================== new sale

    /**
     * Price of a new sale.
     *
     * @param planLines the plan-fee line at list price plus, for a negotiated price, a separate
     *                  "Negotiated price adjustment" line (so subtotal, discount and GST stay correct)
     */
    public record SaleQuote(BigDecimal price, BigDecimal listPrice, BigDecimal joiningFee,
                            List<PricingCalculator.Line> planLines, boolean negotiated, String reason,
                            boolean keepOnRenewal, BigDecimal discountPct) {
    }

    public SaleQuote quoteSale(MembershipPlan plan, LocalDate today, BigDecimal customPrice, String customReason,
                               boolean keepOnRenewal, String discountCode) {
        PlanPrice pp = planPrice(plan);
        BigDecimal list = pp.priceOn(today);
        BigDecimal fee = pp.joiningFeeOn(today);
        List<PricingCalculator.Line> lines = new ArrayList<>();
        lines.add(new PricingCalculator.Line(InvoiceLineType.PLAN_FEE, plan.getName() + " membership", list));
        if (customPrice == null) {
            return new SaleQuote(list, list, fee, lines, false, null, false, BigDecimal.ZERO);
        }
        BigDecimal custom = Money.of(customPrice);
        String reason = customReason == null ? "" : customReason.trim();
        BigDecimal pct = validateOverride(list, custom, reason, discountCode);
        lines.add(new PricingCalculator.Line(InvoiceLineType.PRICE_ADJUSTMENT, "Negotiated price adjustment",
                custom.subtract(list)));
        return new SaleQuote(custom, list, fee, lines, true, reason, keepOnRenewal, pct);
    }

    /** @return the discount in percent */
    BigDecimal validateOverride(BigDecimal list, BigDecimal custom, String reason, String discountCode) {
        UserPrincipal caller = CurrentUser.find()
                .orElseThrow(() -> new ForbiddenException("A custom price needs an authenticated manager"));
        if (!caller.hasAnyRole(MAY_OVERRIDE)) {
            throw new ForbiddenException("Only managers and admins can sell at a custom price");
        }
        if (reason.length() < MIN_REASON_LENGTH) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                    "A reason (at least " + MIN_REASON_LENGTH + " characters) is required for a custom price");
        }
        if (discountCode != null && !discountCode.isBlank()) {
            throw new BusinessRuleException("A discount code cannot be combined with a custom price");
        }
        if (custom.compareTo(list) >= 0) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                    "The custom price must be below the list price of " + list);
        }
        BigDecimal pct = PriceResolver.overrideDiscountPct(list, custom);
        if (!caller.isOrgWide() && pct.compareTo(props.maxOverrideDiscountPct()) > 0) {
            throw new BusinessRuleException("A " + pct.stripTrailingZeros().toPlainString() + "% discount exceeds the "
                    + props.maxOverrideDiscountPct().stripTrailingZeros().toPlainString()
                    + "% a manager may give; ask a gym admin");
        }
        return pct;
    }

    // ================================================================== renewals

    /**
     * Price of renewing {@code source} onto {@code target} with a term starting {@code termStart},
     * invoiced on {@code invoiceDate}. For autopay the debit is assumed at invoice time plus the
     * pre-debit notice (never earlier than now plus that notice).
     */
    public Resolution resolveRenewal(Subscription source, MembershipPlan target, LocalDate invoiceDate,
                                     LocalDate termStart, boolean autopay) {
        PlanPrice pp = planPrice(target);
        boolean samePlan = target.getId().equals(source.getPlan().getId());
        boolean soldBefore = pp.scheduled() != null && soldBefore(source, pp.scheduled().id());
        Instant now = Instant.now(clock);
        Instant planned = invoiceDate.atStartOfDay(clock.getZone()).toInstant();
        Instant debitAt = (planned.isAfter(now) ? planned : now).plus(paymentProps.autopay().preDebitNotice());
        List<Notice> sent = autopay && samePlan
                ? notices.findCovering(source.getMember().getId(), target.getId(), BigDecimal.ZERO).stream()
                        .map(n -> new Notice(n.getNewPrice(), n.getNotifiedAt())).toList()
                : List.of();
        return PriceResolver.resolveRenewal(new RenewalRequest(pp, samePlan, invoiceDate, termStart, source.getPrice(),
                source.negotiatedRenewalPrice(), source.getLockedPrice(), source.getPriceProtectedUntil(), soldBefore,
                autopay, debitAt, props.priceChangeNoticeDays(), sent));
    }

    /** What the next autopay renewal of {@code source} will be charged (for pre-checks and the upcoming list). */
    public Resolution nextAutopayRenewal(Subscription source) {
        LocalDate today = LocalDate.now(clock);
        LocalDate invoiceDate = source.getEndDate().minusDays(paymentProps.autopay().leadDays());
        return resolveRenewal(source, source.getPlan(), invoiceDate.isBefore(today) ? today : invoiceDate,
                source.getEndDate().plusDays(1), true);
    }

    /** Invoice lines: the plan fee at list price and, when the member pays less, an explained adjustment. */
    public List<PricingCalculator.Line> renewalLines(MembershipPlan plan, Resolution r, String label) {
        List<PricingCalculator.Line> lines = new ArrayList<>();
        lines.add(new PricingCalculator.Line(InvoiceLineType.PLAN_FEE, label, r.listPrice()));
        if (r.discounted()) {
            String why = switch (r.basis()) {
                case NEGOTIATED -> "Negotiated price adjustment";
                case PRICE_PROTECTED -> "Existing-member price protection (until " + DATE.format(r.protectedUntil()) + ")";
                case NOTICE_HELD -> "Price held at your current rate (price change notice period)";
                case LIST -> "Price adjustment";
            };
            lines.add(new PricingCalculator.Line(InvoiceLineType.PRICE_ADJUSTMENT, why, r.amount().subtract(r.listPrice())));
        }
        return lines;
    }

    /** Copies the pricing facts onto the new renewal term (negotiated price, price protection). */
    public void applyRenewalPricing(Subscription renewal, Subscription source, Resolution r) {
        renewal.priceAs(r.basis(), r.listPrice());
        if (!renewal.getPlan().getId().equals(source.getPlan().getId())) {
            return;
        }
        renewal.inheritNegotiationFrom(source);
        Long changeId = source.getPriceChangeId();
        if (changeId == null && r.lockedPrice() != null) {
            changeId = changes.findFirstByPlanIdAndStatus(source.getPlan().getId(), PriceChangeStatus.SCHEDULED)
                    .map(PlanPriceChange::getId).orElse(null);
        }
        renewal.protectPrice(r.lockedPrice(), r.protectedUntil(), changeId);
    }

    /**
     * Whether the member's membership on this plan predates the change: the change's cut-off is the
     * start of its effective date, or the moment it was scheduled if that is later (effective today).
     */
    boolean soldBefore(Subscription source, Long changeId) {
        PlanPriceChange change = changes.findById(changeId).orElse(null);
        if (change == null) {
            return false;
        }
        Instant cutoff = existingCutoff(change);
        Subscription s = source;
        for (int i = 0; s != null && i < 100; i++) {
            if (!s.getPlan().getId().equals(change.getPlan().getId())) {
                return false;
            }
            if (s.getCreatedAt() != null && s.getCreatedAt().isBefore(cutoff)) {
                return true;
            }
            s = s.getPreviousSubscription();
        }
        return false;
    }

    Instant existingCutoff(PlanPriceChange change) {
        Instant dayStart = change.getEffectiveDate().atStartOfDay(clock.getZone()).toInstant();
        Instant created = change.getCreatedAt();
        return created != null && created.isAfter(dayStart) ? created : dayStart;
    }

    public PricingProperties settings() {
        return props;
    }
}
