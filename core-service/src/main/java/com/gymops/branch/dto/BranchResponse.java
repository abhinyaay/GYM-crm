package com.gymops.branch.dto;

import com.gymops.branch.BranchStatus;
import com.gymops.branch.Facility;
import com.gymops.branch.GymBranch;
import com.gymops.branch.OperatingHours;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

public record BranchResponse(
        Long id, String code, String name, String phone, String email,
        String addressLine1, String addressLine2, String city, String state, String postalCode, String country,
        String timezone, int capacity, BranchStatus status,
        List<Hours> operatingHours, List<FacilityResponse> facilities,
        Instant createdAt, Instant updatedAt, long version, String gstin, String gstStateCode) {

    public record Hours(int dayOfWeek, LocalTime openTime, LocalTime closeTime, boolean closed) {
        static Hours from(OperatingHours h) {
            return new Hours(h.getDayOfWeek(), h.getOpenTime(), h.getCloseTime(), h.isClosed());
        }
    }

    public record FacilityResponse(Long id, String name, String description, boolean available) {
        public static FacilityResponse from(Facility f) {
            return new FacilityResponse(f.getId(), f.getName(), f.getDescription(), f.isAvailable());
        }
    }

    public static BranchResponse from(GymBranch b) {
        return new BranchResponse(b.getId(), b.getCode(), b.getName(), b.getPhone(), b.getEmail(),
                b.getAddressLine1(), b.getAddressLine2(), b.getCity(), b.getState(), b.getPostalCode(), b.getCountry(),
                b.getTimezone(), b.getCapacity(), b.getStatus(),
                b.getOperatingHours().stream().map(Hours::from).toList(),
                b.getFacilities().stream().map(FacilityResponse::from).toList(),
                b.getCreatedAt(), b.getUpdatedAt(), b.getVersion(), b.getGstin(), b.getGstStateCode());
    }
}
