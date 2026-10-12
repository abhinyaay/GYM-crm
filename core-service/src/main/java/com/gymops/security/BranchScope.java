package com.gymops.security;

import java.util.Set;
import java.util.TreeSet;

/**
 * Branch filter for queries: {@code all=true} means unrestricted; otherwise {@code ids} lists the
 * permitted branches (never empty, so it is always a valid SQL IN-list).
 */
public record BranchScope(boolean all, Set<Long> ids) {

    public static final BranchScope ALL = new BranchScope(true, Set.of(-1L));

    public static BranchScope of(Set<Long> ids) {
        return new BranchScope(false, ids.isEmpty() ? Set.of(-1L) : Set.copyOf(ids));
    }

    public boolean includes(Long branchId) {
        return all || ids.contains(branchId);
    }

    /** Stable key for caches keyed by scope. */
    public String cacheKey() {
        return all ? "all" : new TreeSet<>(ids).toString();
    }
}
