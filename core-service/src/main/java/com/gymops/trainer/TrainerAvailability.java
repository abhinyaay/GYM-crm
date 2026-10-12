package com.gymops.trainer;

import com.gymops.branch.GymBranch;
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

/** A recurring weekly slot in which the trainer can be booked. */
@Getter
@Entity
@Table(name = "trainer_availability")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrainerAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trainer_id")
    private Trainer trainer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private GymBranch branch;

    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    public TrainerAvailability(GymBranch branch, int dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.branch = branch;
        this.dayOfWeek = (short) dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    void attachTo(Trainer trainer) {
        this.trainer = trainer;
    }

    public boolean covers(Long branchId, int day, LocalTime from, LocalTime to) {
        return branch.getId().equals(branchId) && dayOfWeek == day && !from.isBefore(startTime) && !to.isAfter(endTime);
    }

    public boolean overlaps(TrainerAvailability other) {
        return dayOfWeek == other.dayOfWeek && startTime.isBefore(other.endTime) && other.startTime.isBefore(endTime);
    }
}
