package com.gymops.pricing;

import com.gymops.common.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Pure price resolution (no I/O), so every rule about "which price applies" is unit-tested.
 *
 * <p>New sale: the plan's list price on the sale date. A scheduled change whose effective date has
 * arrived counts even before the hourly job has written it to the plan (lazy evaluation).
 *
 * <p>Renewal of the same plan, lowest applicable price wins:
 * <ol>
 *   <li>list price on the invoice date;</li>
 *   <li>a negotiated price the member keeps on renewals;</li>
 *   <li>a price-protection lock whose protection date is on/after the renewal term's start
 *       (also derived from a due-but-unapplied PROTECT_EXISTING change for members sold before it);</li>
 * </ol>
 * then, for an <b>autopay</b> renewal only: if that price is higher than the member's current term price
 * and no notice announcing at least that price was sent {@code noticeDays} before the debit, the renewal
 * is charged at the current price instead ({@link PriceBasis#NOTICE_HELD}); the new price applies from
 * the next renewal. Renewing onto a different plan is a new purchase at that plan's list price.
 */
public final class PriceResolver {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PriceResolver() {
    }

    /** A scheduled (not yet applied) change of the plan. */
    public record ScheduledChange(Long id, BigDecimal oldPrice, BigDecimal newPrice, BigDecimal newJoiningFee,
                                  LocalDate effectiveDate, ExistingMemberPolicy policy, LocalDate protectedUntil) {

        boolean dueBy(LocalDate date) {
            return !effectiveDate.isAfter(date);
        }

        boolean protectsExisting() {
            return policy == ExistingMemberPolicy.PROTECT_EXISTING && newPrice.compareTo(oldPrice) > 0;
        }
    }

    /** The plan's stored price and fee plus its pending scheduled change, if any. */
    public record PlanPrice(BigDecimal price, BigDecimal joiningFee, ScheduledChange scheduled) {

        public BigDecimal priceOn(LocalDate date) {
            return scheduled != null && scheduled.dueBy(date) ? scheduled.newPrice() : price;
        }

        public BigDecimal joiningFeeOn(LocalDate date) {
            return scheduled != null && scheduled.dueBy(date) ? scheduled.newJoiningFee() : joiningFee;
        }
    }

    public record Notice(BigDecimal newPrice, Instant notifiedAt) {
    }

    /**
     * @param samePlan              renewing onto the plan the member already holds
     * @param invoiceDate           date the renewal is invoiced (prices are those in force on this date)
     * @param termStart             first day of the renewal term
     * @param currentPrice          the price of the term being renewed
     * @param negotiatedPrice       the source's negotiated price when it is kept on renewals, else null
     * @param lockedPrice           the source's protected price, else null
     * @param protectedUntil        last term-start date the protected price applies to, else null
     * @param soldBeforeScheduled   the source term was sold before the plan's scheduled change took effect
     * @param autopay               an automatic (token) renewal; manual renewals show the price before payment
     * @param debitAt               when the autopay debit will happen
     * @param noticeDays            minimum notice before the first debit at a higher price
     * @param notices               price-rise notices sent to this member for this plan
     */
    public record RenewalRequest(PlanPrice plan, boolean samePlan, LocalDate invoiceDate, LocalDate termStart,
                                 BigDecimal currentPrice, BigDecimal negotiatedPrice, BigDecimal lockedPrice,
                                 LocalDate protectedUntil, boolean soldBeforeScheduled, boolean autopay, Instant debitAt,
                                 int noticeDays, List<Notice> notices) {
    }

    /**
     * @param amount         price of the renewal term
     * @param listPrice      the plan's list price on the invoice date
     * @param intended       price that would apply without the notice rule (equal to amount unless NOTICE_HELD)
     * @param lockedPrice    price protection the new term carries forward (null when none applies any more)
     * @param protectedUntil protection date carried forward with {@code lockedPrice}
     */
    public record Resolution(BigDecimal amount, BigDecimal listPrice, BigDecimal intended, PriceBasis basis,
                             BigDecimal lockedPrice, LocalDate protectedUntil) {

        public boolean discounted() {
            return amount.compareTo(listPrice) != 0;
        }
    }

    public static Resolution resolveRenewal(RenewalRequest r) {
        BigDecimal list = r.plan().priceOn(r.invoiceDate());
        if (!r.samePlan()) {
            return new Resolution(list, list, list, PriceBasis.LIST, null, null);
        }
        BigDecimal locked = r.lockedPrice();
        LocalDate until = r.protectedUntil();
        ScheduledChange pending = r.plan().scheduled();
        if (pending != null && pending.dueBy(r.invoiceDate()) && pending.protectsExisting() && r.soldBeforeScheduled()) {
            // the job has not applied the change yet: protect exactly as applying it would
            locked = locked == null ? pending.oldPrice() : Money.min(locked, pending.oldPrice());
            until = until == null || pending.protectedUntil().isAfter(until) ? pending.protectedUntil() : until;
        }
        BigDecimal candidate = list;
        PriceBasis basis = PriceBasis.LIST;
        if (r.negotiatedPrice() != null && r.negotiatedPrice().compareTo(candidate) < 0) {
            candidate = r.negotiatedPrice();
            basis = PriceBasis.NEGOTIATED;
        }
        boolean protectionApplies = locked != null && until != null && !r.termStart().isAfter(until);
        if (protectionApplies && locked.compareTo(candidate) < 0) {
            candidate = locked;
            basis = PriceBasis.PRICE_PROTECTED;
        }
        BigDecimal carryLock = protectionApplies ? locked : null;
        LocalDate carryUntil = protectionApplies ? until : null;
        if (r.autopay() && candidate.compareTo(r.currentPrice()) > 0 && !noticeGiven(r, candidate)) {
            return new Resolution(r.currentPrice(), list, candidate, PriceBasis.NOTICE_HELD, carryLock, carryUntil);
        }
        return new Resolution(candidate, list, candidate, basis, carryLock, carryUntil);
    }

    /** A notice for at least {@code price} sent no later than {@code noticeDays} before the debit. */
    static boolean noticeGiven(RenewalRequest r, BigDecimal price) {
        Instant latest = r.debitAt().minus(Duration.ofDays(r.noticeDays()));
        return r.notices().stream()
                .anyMatch(n -> n.newPrice().compareTo(price) >= 0 && !n.notifiedAt().isAfter(latest));
    }

    /**
     * Projects when an autopay member is first debited at a new price: the first renewal invoiced on or
     * after the effective date, whose term starts after any protection, and whose debit falls after the
     * notice period (counted from {@code notifiedOn}, conservatively one day extra since the exact time
     * of the debit is not known in advance).
     *
     * @param termEnd          end of the member's current term
     * @param nextTermEnd      end date of a term starting on the given date
     * @param leadDays         renewal invoices are raised this many days before the term ends
     * @param preDebitDays     the debit follows the invoice after this many days
     */
    public static Optional<LocalDate> projectFirstDebitAtNewPrice(LocalDate termEnd, UnaryOperator<LocalDate> nextTermEnd,
                                                                  int leadDays, int preDebitDays, LocalDate today,
                                                                  LocalDate effectiveDate, LocalDate protectedUntil,
                                                                  LocalDate notifiedOn, int noticeDays) {
        LocalDate earliestDebit = notifiedOn.plusDays(noticeDays + 1L);
        LocalDate end = termEnd;
        for (int i = 0; i < 240; i++) {
            LocalDate invoice = end.minusDays(leadDays).isBefore(today) ? today : end.minusDays(leadDays);
            LocalDate debit = invoice.plusDays(preDebitDays);
            LocalDate start = end.plusDays(1);
            boolean afterEffective = !invoice.isBefore(effectiveDate);
            boolean afterProtection = protectedUntil == null || start.isAfter(protectedUntil);
            if (afterEffective && afterProtection && !debit.isBefore(earliestDebit)) {
                return Optional.of(debit);
            }
            end = nextTermEnd.apply(start);
        }
        return Optional.empty();
    }

    /** Discount of a custom price against the list price, in percent (2 decimals). */
    public static BigDecimal overrideDiscountPct(BigDecimal listPrice, BigDecimal customPrice) {
        if (listPrice.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return listPrice.subtract(customPrice).multiply(HUNDRED).divide(listPrice, 2, RoundingMode.HALF_UP);
    }

    /** Whole days of a pre-debit notice duration, rounded up (24h -> 1 day). */
    public static int days(Duration duration) {
        long hours = duration.toHours();
        return (int) ((hours + 23) / 24);
    }
}
