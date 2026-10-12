package com.gymops.growth.campaign;

import java.util.List;

/**
 * Source of churn-risk scores (the Python analytics service in production; a mock in tests).
 * Implementations throw {@link ChurnRiskUnavailableException} when scores cannot be fetched.
 */
public interface ChurnRiskSource {

    /** Member ids at or above {@code band}, optionally for one branch, at most {@code limit} of them. */
    List<Long> membersAtRisk(ChurnBand band, Long branchId, int limit);

    class ChurnRiskUnavailableException extends RuntimeException {
        public ChurnRiskUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
