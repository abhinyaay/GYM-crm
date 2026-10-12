package com.gymops.pricing.gst;

import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;

/** A branch's GST registration as stored: normalised GSTIN and the matching state code. */
public record GstRegistration(String gstin, String stateCode) {

    /**
     * Resolves what to store from the branch form: the state code comes from the GSTIN when one is
     * given (and must match an explicitly entered code), else from the entered code, else from the
     * branch's state name.
     */
    public static GstRegistration resolve(String gstin, String stateCode, String stateName) {
        String g = gstin == null || gstin.isBlank() ? null : Gstin.normalize(gstin);
        String code = stateCode == null || stateCode.isBlank() ? null : stateCode.trim();
        if (g != null) {
            if (!Gstin.isValid(g)) {
                throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Invalid GSTIN " + g);
            }
            if (code != null && !code.equals(Gstin.stateCode(g))) {
                throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "GSTIN " + g + " is registered in state "
                        + Gstin.stateCode(g) + ", not " + code);
            }
            return new GstRegistration(g, Gstin.stateCode(g));
        }
        if (code != null) {
            if (!IndianStates.isValidCode(code)) {
                throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Unknown GST state code " + code);
            }
            return new GstRegistration(null, code);
        }
        return new GstRegistration(null, IndianStates.codeFor(stateName).orElse(null));
    }
}
