package com.gymops.branch.dto;

import com.gymops.pricing.gst.ValidGstin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BranchRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{2,20}$", message = "2-20 letters, digits or hyphens") String code,
        @NotBlank @Size(max = 120) String name,
        @Pattern(regexp = "^\\+?[0-9 -]{7,20}$", message = "invalid phone number") String phone,
        @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 200) String addressLine1,
        @Size(max = 200) String addressLine2,
        @NotBlank @Size(max = 80) String city,
        @Size(max = 80) String state,
        @Size(max = 20) String postalCode,
        @Pattern(regexp = "^[A-Z]{2}$", message = "ISO-3166 alpha-2 country code") String country,
        @NotBlank String timezone,
        @Min(1) @Max(10000) int capacity,
        @ValidGstin String gstin,
        @Pattern(regexp = "^[0-9]{2}$", message = "2-digit GST state code") String gstStateCode) {
}
