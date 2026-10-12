package com.gymops.member.dto;

import com.gymops.member.MemberStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MemberStatusRequest(@NotNull MemberStatus status, @Size(max = 300) String reason) {
}
