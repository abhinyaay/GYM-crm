package com.gymops.branch.dto;

import com.gymops.branch.GymBranch;

public record BranchSummary(Long id, String code, String name, String city, String addressLine1, String phone) {

    public static BranchSummary from(GymBranch b) {
        return new BranchSummary(b.getId(), b.getCode(), b.getName(), b.getCity(), b.getAddressLine1(), b.getPhone());
    }
}
