package com.gymops.member.dto;

import com.gymops.member.Member;
import com.gymops.member.MemberStatus;
import com.gymops.member.MembershipState;
import java.time.Instant;
import java.time.LocalDate;

public record MemberListItem(Long id, String memberCode, String fullName, String email, String phone,
                             Long homeBranchId, MemberStatus status, MembershipState membershipState,
                             LocalDate membershipEndDate, Instant registeredAt) {

    public static MemberListItem from(Member m, MembershipState state, LocalDate endDate) {
        return new MemberListItem(m.getId(), m.getMemberCode(), m.fullName(), m.getEmail(), m.getPhone(),
                m.getHomeBranch().getId(), m.getStatus(), state, endDate, m.getRegisteredAt());
    }
}
