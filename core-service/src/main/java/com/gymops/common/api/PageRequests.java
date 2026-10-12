package com.gymops.common.api;

import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds bounded, whitelisted page requests. Sorting on arbitrary client-supplied properties is
 * rejected so clients cannot sort on unindexed or non-existent columns.
 */
public final class PageRequests {

    public static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {
    }

    public static Pageable of(int page, int size, String sort, Set<String> allowedSortFields, Sort defaultSort) {
        if (page < 0) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, parseSort(sort, allowedSortFields, defaultSort));
    }

    static Sort parseSort(String sort, Set<String> allowed, Sort defaultSort) {
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (!allowed.contains(property)) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                    "Unsupported sort property '" + property + "'. Allowed: " + allowed);
        }
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(direction, property);
    }
}
