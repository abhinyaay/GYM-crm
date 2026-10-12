package com.gymops.branch;

import com.gymops.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Setter
@Entity
@Table(name = "gym_branches")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GymBranch extends BaseEntity {

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String phone;
    private String email;

    @Column(name = "address_line1", nullable = false)
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(nullable = false)
    private String city;

    private String state;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(nullable = false)
    private String country;

    @Column(nullable = false)
    private String timezone;

    /** GST registration of this branch (null = unregistered); see com.gymops.pricing.gst. */
    private String gstin;

    @Column(name = "gst_state_code")
    private String gstStateCode;

    @Column(nullable = false)
    private int capacity;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BranchStatus status = BranchStatus.ACTIVE;

    @Setter(AccessLevel.NONE)
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Setter(AccessLevel.NONE)
    @OrderBy("dayOfWeek ASC")
    @OneToMany(mappedBy = "branch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OperatingHours> operatingHours = new ArrayList<>();

    @Setter(AccessLevel.NONE)
    @OrderBy("name ASC")
    @OneToMany(mappedBy = "branch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Facility> facilities = new ArrayList<>();

    public GymBranch(String code, String name) {
        this.code = code.trim().toUpperCase();
        this.name = name;
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    public void changeStatus(BranchStatus newStatus) {
        this.status = newStatus;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
        this.status = BranchStatus.INACTIVE;
    }

    /**
     * Replaces the weekly schedule. Existing rows are updated in place per weekday (rather than
     * deleted and re-inserted) because Hibernate flushes inserts before deletes, which would
     * violate UNIQUE(branch_id, day_of_week).
     */
    public void replaceOperatingHours(List<OperatingHours> hours) {
        java.util.Map<Short, OperatingHours> requested = new java.util.HashMap<>();
        hours.forEach(h -> requested.put(h.getDayOfWeek(), h));
        operatingHours.removeIf(existing -> !requested.containsKey(existing.getDayOfWeek()));
        requested.values().forEach(h -> operatingHours.stream()
                .filter(existing -> existing.getDayOfWeek() == h.getDayOfWeek())
                .findFirst()
                .ifPresentOrElse(existing -> existing.copyFrom(h), () -> {
                    h.attachTo(this);
                    operatingHours.add(h);
                }));
        operatingHours.sort(java.util.Comparator.comparingInt(OperatingHours::getDayOfWeek));
    }

    public Facility addFacility(String name, String description) {
        Facility f = new Facility(this, name, description);
        facilities.add(f);
        return f;
    }

    public Optional<Facility> facility(Long facilityId) {
        return facilities.stream().filter(f -> facilityId.equals(f.getId())).findFirst();
    }

    /** Whether the branch is open at the given instant, evaluated in the branch's own timezone. */
    public boolean isOpenAt(Instant instant) {
        if (status != BranchStatus.ACTIVE) {
            return false;
        }
        if (operatingHours.isEmpty()) {
            return true; // no schedule configured = unrestricted (e.g. 24x7 site)
        }
        ZonedDateTime local = instant.atZone(zone());
        DayOfWeek day = local.getDayOfWeek();
        LocalTime time = local.toLocalTime();
        return operatingHours.stream()
                .filter(h -> h.getDayOfWeek() == day.getValue())
                .findFirst()
                .map(h -> !h.isClosed() && !time.isBefore(h.getOpenTime()) && time.isBefore(h.getCloseTime()))
                .orElse(false);
    }
}
