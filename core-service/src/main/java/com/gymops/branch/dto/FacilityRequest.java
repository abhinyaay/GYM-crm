package com.gymops.branch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FacilityRequest(@NotBlank @Size(max = 80) String name, @Size(max = 300) String description,
                              Boolean available) {
}
