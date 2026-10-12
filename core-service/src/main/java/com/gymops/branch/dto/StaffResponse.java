package com.gymops.branch.dto;

import com.gymops.branch.StaffAssignment;
import com.gymops.user.RoleName;
import java.time.Instant;
import java.util.Set;

public record StaffResponse(Long userId, String fullName, String email, Set<RoleName> roles, Instant assignedAt) {

    public static StaffResponse from(StaffAssignment a) {
        return new StaffResponse(a.getUser().getId(), a.getUser().getFullName(), a.getUser().getEmail(),
                a.getUser().roleNames(), a.getAssignedAt());
    }
}
