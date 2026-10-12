package com.gymops.payment;

import com.gymops.common.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Pure invoice arithmetic (no I/O) so every rounding rule is unit-tested.
 *
 * <pre>
 * subtotal = max(0, sum(lines))           // credits are negative lines
 * discount = min(discount(subtotal), subtotal)
 * tax      = round((subtotal - discount) * taxRate, 2, HALF_UP)
 * total    = subtotal - discount + tax
 * </pre>
 */
public final class PricingCalculator {

    public record Line(InvoiceLineType type, String description, BigDecimal amount) {
    }

    public record Quote(List<Line> lines, BigDecimal subtotal, BigDecimal discountAmount, BigDecimal taxRate,
                        BigDecimal taxAmount, BigDecimal total) {
    }

    private PricingCalculator() {
    }

    public static Quote quote(List<Line> lines, Optional<Discount> discount, BigDecimal taxRate) {
        BigDecimal sum = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal subtotal = Money.of(Money.max(sum, BigDecimal.ZERO));
        BigDecimal discountAmount = discount.map(d -> d.amountFor(subtotal)).orElse(Money.ZERO);
        BigDecimal taxable = subtotal.subtract(discountAmount);
        BigDecimal tax = taxable.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = Money.of(taxable.add(tax));
        return new Quote(List.copyOf(lines), subtotal, discountAmount, taxRate, tax, total);
    }
}
