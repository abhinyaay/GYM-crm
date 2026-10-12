package com.gymops.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmergencyContactRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 40) String relationship,
        @NotBlank @Pattern(regexp = "^\\+?[0-9 -]{7,20}$", message = "invalid phone number") String phone,
        @Email @Size(max = 254) String email,
        boolean primary) {
}
