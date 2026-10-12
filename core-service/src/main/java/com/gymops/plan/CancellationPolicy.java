package com.gymops.plan;

/**
 * <ul>
 *   <li>NON_REFUNDABLE: no refund on cancellation</li>
 *   <li>PRORATED: refund the unused, unfrozen portion minus the plan cancellation fee</li>
 *   <li>COOLING_OFF_FULL_REFUND: full refund within {@code coolingOffDays} of activation, prorated afterwards</li>
 * </ul>
 */
public enum CancellationPolicy {
    NON_REFUNDABLE,
    PRORATED,
    COOLING_OFF_FULL_REFUND
}
