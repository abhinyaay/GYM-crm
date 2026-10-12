package com.gymops.branch.dto;

import jakarta.validation.constraints.NotNull;

public record StaffAssignmentRequest(@NotNull Long userId) {
}
