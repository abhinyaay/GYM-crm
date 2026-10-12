package com.gymops.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PricingCalculatorTest {

    private static final BigDecimal GST = new BigDecimal("0.18");

    private static PricingCalculator.Line line(InvoiceLineType type, String amount) {
        return new PricingCalculator.Line(type, type.name(), new BigDecimal(amount));
    }

    @Test
    void appliesTaxOnSubtotal() {
        var quote = PricingCalculator.quote(List.of(line(InvoiceLineType.PLAN_FEE, "2499.00"),
                line(InvoiceLineType.JOINING_FEE, "1000.00")), Optional.empty(), GST);
        assertThat(quote.subtotal()).isEqualByComparingTo("3499.00");
        assertThat(quote.taxAmount()).isEqualByComparingTo("629.82");
        assertThat(quote.total()).isEqualByComparingTo("4128.82");
    }

    @Test
    void percentageDiscountBeforeTaxWithHalfUpRounding() {
        Discount tenPercent = new Discount("WELCOME10", DiscountType.PERCENTAGE, new BigDecimal("10"), LocalDate.MIN);
        var quote = PricingCalculator.quote(List.of(line(InvoiceLineType.PLAN_FEE, "2499.00")), Optional.of(tenPercent), GST);
        assertThat(quote.discountAmount()).isEqualByComparingTo("249.90");
        assertThat(quote.taxAmount()).isEqualByComparingTo("404.84");   // 2249.10 * 0.18 = 404.838 -> HALF_UP
    }

    @Test
    void fixedDiscountNeverExceedsSubtotal() {
        Discount big = new Discount("BIG", DiscountType.FIXED_AMOUNT, new BigDecimal("5000"), LocalDate.MIN);
        var quote = PricingCalculator.quote(List.of(line(InvoiceLineType.PLAN_FEE, "1499.00")), Optional.of(big), GST);
        assertThat(quote.discountAmount()).isEqualByComparingTo("1499.00");
        assertThat(quote.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void prorationCreditReducesSubtotalButNeverBelowZero() {
        var quote = PricingCalculator.quote(List.of(line(InvoiceLineType.PLAN_FEE, "22999.00"),
                line(InvoiceLineType.PRORATION_CREDIT, "-1500.00")), Optional.empty(), GST);
        assertThat(quote.subtotal()).isEqualByComparingTo("21499.00");
        var negative = PricingCalculator.quote(List.of(line(InvoiceLineType.PLAN_FEE, "100.00"),
                line(InvoiceLineType.PRORATION_CREDIT, "-500.00")), Optional.empty(), GST);
        assertThat(negative.subtotal()).isEqualByComparingTo("0.00");
        assertThat(negative.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void discountUsabilityRules() {
        Discount d = new Discount("LIMITED", DiscountType.PERCENTAGE, new BigDecimal("5"), LocalDate.of(2026, 1, 1));
        d.setValidUntil(LocalDate.of(2026, 1, 31));
        d.setMaxRedemptions(1);
        assertThat(d.isUsableOn(LocalDate.of(2025, 12, 31))).isFalse();
        assertThat(d.isUsableOn(LocalDate.of(2026, 1, 15))).isTrue();
        assertThat(d.isUsableOn(LocalDate.of(2026, 2, 1))).isFalse();
        d.setActive(false);
        assertThat(d.isUsableOn(LocalDate.of(2026, 1, 15))).isFalse();
    }
}
