package com.gymops.pricing.gst;

import com.gymops.common.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * GST arithmetic for documents. Intra-state supply (supplier and place of supply in the same state):
 * CGST + SGST, each half the rate; the stored tax amount is split so the halves always add up to it
 * (an odd paisa goes to CGST). Inter-state: IGST at the full rate.
 */
public final class GstCalculator {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private GstCalculator() {
    }

    public record TaxComponent(String name, BigDecimal ratePct, BigDecimal amount) {
    }

    /** Where the supplier is registered and where the service is supplied, by GST state code. */
    public static boolean isInterState(String supplierStateCode, String placeOfSupplyCode) {
        return supplierStateCode != null && placeOfSupplyCode != null && !supplierStateCode.equals(placeOfSupplyCode);
    }

    public static List<TaxComponent> split(BigDecimal taxAmount, BigDecimal taxRate, boolean interState) {
        BigDecimal ratePct = taxRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
        if (interState) {
            return List.of(new TaxComponent("IGST", ratePct, Money.of(taxAmount)));
        }
        BigDecimal half = taxAmount.divide(TWO, 2, RoundingMode.HALF_UP);
        BigDecimal halfRate = ratePct.divide(TWO).stripTrailingZeros();
        return List.of(new TaxComponent("CGST", halfRate, half),
                new TaxComponent("SGST", halfRate, Money.of(taxAmount.subtract(half))));
    }

    /** Splits a tax-inclusive amount (e.g. a refund) into taxable value and tax at {@code taxRate}. */
    public static BigDecimal[] fromInclusive(BigDecimal inclusive, BigDecimal taxRate) {
        BigDecimal taxable = inclusive.divide(BigDecimal.ONE.add(taxRate), 2, RoundingMode.HALF_UP);
        return new BigDecimal[] {taxable, Money.of(inclusive.subtract(taxable))};
    }
}
