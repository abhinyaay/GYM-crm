package com.gymops.member.dto;

import com.gymops.member.Gender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Create / full update of a member by staff (or self-service update of own contact details). */
public record MemberRequest(
        @NotNull Long homeBranchId,
        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 80) String lastName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "^\\+?[0-9 -]{7,20}$", message = "invalid phone number") String phone,
        @Past LocalDate dateOfBirth,
        Gender gender,
        @Size(max = 200) String addressLine1,
        @Size(max = 80) String city,
        @Size(max = 80) String state,
        @Size(max = 20) String postalCode,
        @Size(max = 1000) String notes,
        @Size(max = 3) List<@Valid EmergencyContactRequest> emergencyContacts) {
}
