package com.gymops.branch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "branch_operating_hours")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperatingHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private GymBranch branch;

    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    @Column(nullable = false)
    private boolean closed;

    public OperatingHours(int dayOfWeek, LocalTime openTime, LocalTime closeTime, boolean closed) {
        this.dayOfWeek = (short) dayOfWeek;
        this.openTime = closed ? null : openTime;
        this.closeTime = closed ? null : closeTime;
        this.closed = closed;
    }

    void copyFrom(OperatingHours other) {
        this.openTime = other.openTime;
        this.closeTime = other.closeTime;
        this.closed = other.closed;
    }

    void attachTo(GymBranch branch) {
        this.branch = branch;
    }
}
