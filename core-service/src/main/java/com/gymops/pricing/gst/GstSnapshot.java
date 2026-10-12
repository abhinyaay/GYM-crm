package com.gymops.pricing.gst;

import com.gymops.branch.GymBranch;
import com.gymops.member.Member;

/**
 * Supplier registration and place of supply for an invoice. Place of supply for a member (an
 * unregistered recipient) is the state on the member's address when it is on record, otherwise the
 * supplier's own state (IGST Act s.12(2)(b)); a different state makes the supply inter-state (IGST).
 */
public record GstSnapshot(String gstin, String supplierStateCode, String placeOfSupplyCode) {

    public static GstSnapshot of(GymBranch branch, Member member) {
        String supplierState = branch.getGstStateCode() != null ? branch.getGstStateCode()
                : IndianStates.codeFor(branch.getState()).orElse(null);
        String place = IndianStates.codeFor(member.getState()).orElse(supplierState);
        return new GstSnapshot(branch.getGstin(), supplierState, place);
    }

    public boolean interState() {
        return GstCalculator.isInterState(supplierStateCode, placeOfSupplyCode);
    }
}
