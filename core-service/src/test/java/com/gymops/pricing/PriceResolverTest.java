package com.gymops.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.pricing.PriceResolver.Notice;
import com.gymops.pricing.PriceResolver.PlanPrice;
import com.gymops.pricing.PriceResolver.RenewalRequest;
import com.gymops.pricing.PriceResolver.Resolution;
import com.gymops.pricing.PriceResolver.ScheduledChange;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PriceResolverTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    static final Instant DEBIT = Instant.parse("2026-10-14T05:00:00Z");

    static BigDecimal m(String v) {
        return new BigDecimal(v);
    }

    static PlanPrice plan(String price) {
        return new PlanPrice(m(price), m("500.00"), null);
    }

    static PlanPrice planWithChange(String price, String newPrice, LocalDate effective, ExistingMemberPolicy policy,
                                    LocalDate protectedUntil) {
        return new PlanPrice(m(price), m("500.00"), new ScheduledChange(7L, m(price), m(newPrice), m("800.00"), effective,
                policy, protectedUntil));
    }

    /** Builder-ish helper: renewal of a term priced {@code current} with nothing special. */
    static RenewalRequest renewal(PlanPrice plan, String current) {
        return new RenewalRequest(plan, true, TODAY, TODAY.plusDays(3), m(current), null, null, null, false, false, DEBIT,
                7, List.of());
    }

    static RenewalRequest with(RenewalRequest r, BigDecimal negotiated, BigDecimal locked, LocalDate until,
                               boolean soldBefore, boolean autopay, List<Notice> notices) {
        return new RenewalRequest(r.plan(), r.samePlan(), r.invoiceDate(), r.termStart(), r.currentPrice(), negotiated,
                locked, until, soldBefore, autopay, r.debitAt(), r.noticeDays(), notices);
    }

    @Nested
    class ListPrices {
        @Test
        void dueScheduledChangeCountsBeforeTheJobAppliesIt() {
            PlanPrice p = planWithChange("2000.00", "2500.00", TODAY, ExistingMemberPolicy.NEW_PRICE_AT_RENEWAL, null);
            assertThat(p.priceOn(TODAY)).isEqualByComparingTo("2500.00");
            assertThat(p.joiningFeeOn(TODAY)).isEqualByComparingTo("800.00");
            assertThat(p.priceOn(TODAY.minusDays(1))).isEqualByComparingTo("2000.00");
        }

        @Test
        void futureChangeDoesNotApplyYet() {
            PlanPrice p = planWithChange("2000.00", "2500.00", TODAY.plusDays(10), ExistingMemberPolicy.NEW_PRICE_AT_RENEWAL,
                    null);
            assertThat(p.priceOn(TODAY)).isEqualByComparingTo("2000.00");
            assertThat(p.joiningFeeOn(TODAY)).isEqualByComparingTo("500.00");
        }
    }

    @Nested
    class ManualRenewals {
        @Test
        void listPriceOnTheInvoiceDate() {
            Resolution r = PriceResolver.resolveRenewal(renewal(plan("2500.00"), "2000.00"));
            assertThat(r.amount()).isEqualByComparingTo("2500.00");
            assertThat(r.basis()).isEqualTo(PriceBasis.LIST);
            assertThat(r.discounted()).isFalse();
        }

        @Test
        void differentPlanIsANewPurchaseAtListPrice() {
            RenewalRequest base = renewal(plan("4000.00"), "2000.00");
            RenewalRequest other = new RenewalRequest(base.plan(), false, TODAY, TODAY, m("2000.00"), m("1000.00"),
                    m("1500.00"), TODAY.plusYears(1), true, true, DEBIT, 7, List.of());
            assertThat(PriceResolver.resolveRenewal(other).amount()).isEqualByComparingTo("4000.00");
        }

        @Test
        void negotiatedPriceKeptOnRenewalButNeverAboveList() {
            RenewalRequest r = with(renewal(plan("2500.00"), "1800.00"), m("1800.00"), null, null, false, false, List.of());
            Resolution kept = PriceResolver.resolveRenewal(r);
            assertThat(kept.amount()).isEqualByComparingTo("1800.00");
            assertThat(kept.basis()).isEqualTo(PriceBasis.NEGOTIATED);
            assertThat(kept.discounted()).isTrue();

            RenewalRequest cheaperList = with(renewal(plan("1500.00"), "1800.00"), m("1800.00"), null, null, false, false,
                    List.of());
            Resolution list = PriceResolver.resolveRenewal(cheaperList);
            assertThat(list.amount()).isEqualByComparingTo("1500.00");
            assertThat(list.basis()).isEqualTo(PriceBasis.LIST);
        }

        @Test
        void protectedPriceAppliesToTermsStartingOnOrBeforeTheProtectionDate() {
            LocalDate start = TODAY.plusDays(3);
            Resolution protectedTerm = PriceResolver.resolveRenewal(with(renewal(plan("2500.00"), "2000.00"), null,
                    m("2000.00"), start, false, false, List.of()));
            assertThat(protectedTerm.amount()).isEqualByComparingTo("2000.00");
            assertThat(protectedTerm.basis()).isEqualTo(PriceBasis.PRICE_PROTECTED);
            assertThat(protectedTerm.lockedPrice()).as("lock carried to the new term").isEqualByComparingTo("2000.00");
            assertThat(protectedTerm.protectedUntil()).isEqualTo(start);

            Resolution expired = PriceResolver.resolveRenewal(with(renewal(plan("2500.00"), "2000.00"), null,
                    m("2000.00"), start.minusDays(1), false, false, List.of()));
            assertThat(expired.amount()).isEqualByComparingTo("2500.00");
            assertThat(expired.basis()).isEqualTo(PriceBasis.LIST);
            assertThat(expired.lockedPrice()).as("expired lock is dropped").isNull();
        }

        @Test
        void dueProtectChangeProtectsExistingMembersEvenBeforeTheJobRan() {
            PlanPrice p = planWithChange("2000.00", "2500.00", TODAY, ExistingMemberPolicy.PROTECT_EXISTING,
                    TODAY.plusMonths(3));
            Resolution existing = PriceResolver.resolveRenewal(with(renewal(p, "2000.00"), null, null, null, true, false,
                    List.of()));
            assertThat(existing.amount()).isEqualByComparingTo("2000.00");
            assertThat(existing.basis()).isEqualTo(PriceBasis.PRICE_PROTECTED);
            assertThat(existing.listPrice()).isEqualByComparingTo("2500.00");

            Resolution newcomer = PriceResolver.resolveRenewal(with(renewal(p, "2000.00"), null, null, null, false, false,
                    List.of()));
            assertThat(newcomer.amount()).as("bought after the change took effect").isEqualByComparingTo("2500.00");
        }

        @Test
        void lowestApplicablePriceWins() {
            Resolution r = PriceResolver.resolveRenewal(with(renewal(plan("3000.00"), "1500.00"), m("1500.00"),
                    m("2000.00"), TODAY.plusYears(1), false, false, List.of()));
            assertThat(r.amount()).isEqualByComparingTo("1500.00");
            assertThat(r.basis()).isEqualTo(PriceBasis.NEGOTIATED);
        }

        @Test
        void manualRenewalIgnoresTheNoticeRule() {
            Resolution r = PriceResolver.resolveRenewal(renewal(plan("2500.00"), "2000.00"));
            assertThat(r.amount()).isEqualByComparingTo("2500.00");
        }
    }

    @Nested
    class AutopayNotice {
        RenewalRequest autopay(List<Notice> notices) {
            return with(renewal(plan("2500.00"), "2000.00"), null, null, null, false, true, notices);
        }

        @Test
        void riseWithoutNoticeIsHeldAtTheCurrentPrice() {
            Resolution r = PriceResolver.resolveRenewal(autopay(List.of()));
            assertThat(r.amount()).isEqualByComparingTo("2000.00");
            assertThat(r.basis()).isEqualTo(PriceBasis.NOTICE_HELD);
            assertThat(r.intended()).isEqualByComparingTo("2500.00");
            assertThat(r.listPrice()).isEqualByComparingTo("2500.00");
        }

        @Test
        void noticeSentLessThanNoticeDaysBeforeTheDebitDoesNotCount() {
            Notice late = new Notice(m("2500.00"), DEBIT.minus(Duration.ofDays(7)).plusSeconds(1));
            assertThat(PriceResolver.resolveRenewal(autopay(List.of(late))).basis()).isEqualTo(PriceBasis.NOTICE_HELD);
        }

        @Test
        void noticeSentInTimeAllowsTheNewPrice() {
            Notice onTime = new Notice(m("2500.00"), DEBIT.minus(Duration.ofDays(7)));
            Resolution r = PriceResolver.resolveRenewal(autopay(List.of(onTime)));
            assertThat(r.amount()).isEqualByComparingTo("2500.00");
            assertThat(r.basis()).isEqualTo(PriceBasis.LIST);
        }

        @Test
        void noticeForALowerPriceDoesNotCoverAHigherOne() {
            Notice lower = new Notice(m("2400.00"), DEBIT.minus(Duration.ofDays(30)));
            assertThat(PriceResolver.resolveRenewal(autopay(List.of(lower))).basis()).isEqualTo(PriceBasis.NOTICE_HELD);
            Notice higher = new Notice(m("2600.00"), DEBIT.minus(Duration.ofDays(30)));
            assertThat(PriceResolver.resolveRenewal(autopay(List.of(higher))).amount()).isEqualByComparingTo("2500.00");
        }

        @Test
        void priceDecreaseNeedsNoNotice() {
            RenewalRequest r = with(renewal(plan("1800.00"), "2000.00"), null, null, null, false, true, List.of());
            assertThat(PriceResolver.resolveRenewal(r).amount()).isEqualByComparingTo("1800.00");
        }

        @Test
        void protectionEndingIsARiseThatNeedsNotice() {
            RenewalRequest r = with(renewal(plan("2500.00"), "2000.00"), null, m("2000.00"), TODAY, false, true, List.of());
            Resolution res = PriceResolver.resolveRenewal(r);
            assertThat(res.basis()).isEqualTo(PriceBasis.NOTICE_HELD);
            assertThat(res.amount()).isEqualByComparingTo("2000.00");
        }
    }

    @Nested
    class Projection {
        // monthly terms: a term starting on d ends one month later minus a day
        final java.util.function.UnaryOperator<LocalDate> monthly = start -> start.plusMonths(1).minusDays(1);

        @Test
        void firstRenewalAfterEffectiveDateAndNoticePeriod() {
            // term ends 10 Nov; renewal invoiced 7 Nov, debited 8 Nov; notice today (13 Oct) + 7 days is fine
            assertThat(PriceResolver.projectFirstDebitAtNewPrice(LocalDate.of(2026, 11, 10), monthly, 3, 1, TODAY,
                    TODAY, null, TODAY, 7)).contains(LocalDate.of(2026, 11, 8));
        }

        @Test
        void renewalTooSoonForNoticeMovesToTheFollowingTerm() {
            // term ends 16 Oct: invoice 13 Oct, debit 14 Oct < 21 Oct -> next term (17 Oct - 16 Nov) debited 14 Nov
            assertThat(PriceResolver.projectFirstDebitAtNewPrice(LocalDate.of(2026, 10, 16), monthly, 3, 1, TODAY,
                    TODAY, null, TODAY, 7)).contains(LocalDate.of(2026, 11, 14));
        }

        @Test
        void protectionDefersTheFirstDebit() {
            LocalDate protectedUntil = LocalDate.of(2027, 1, 10);   // terms starting on or before 10 Jan keep the old price
            assertThat(PriceResolver.projectFirstDebitAtNewPrice(LocalDate.of(2026, 11, 10), monthly, 3, 1, TODAY,
                    TODAY, protectedUntil, TODAY, 7)).contains(LocalDate.of(2027, 1, 8));
        }
    }

    @Test
    void overrideDiscountPercent() {
        assertThat(PriceResolver.overrideDiscountPct(m("2000.00"), m("1500.00"))).isEqualByComparingTo("25.00");
        assertThat(PriceResolver.overrideDiscountPct(m("3000.00"), m("1000.00"))).isEqualByComparingTo("66.67");
        assertThat(PriceResolver.overrideDiscountPct(m("0.00"), m("0.00"))).isEqualByComparingTo("0");
        assertThat(PriceResolver.days(Duration.ofHours(24))).isEqualTo(1);
        assertThat(PriceResolver.days(Duration.ofHours(25))).isEqualTo(2);
    }
}
