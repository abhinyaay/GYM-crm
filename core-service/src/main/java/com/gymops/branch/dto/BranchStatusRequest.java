package com.gymops.branch.dto;

import com.gymops.branch.BranchStatus;
import jakarta.validation.constraints.NotNull;

public record BranchStatusRequest(@NotNull BranchStatus status) {
}
