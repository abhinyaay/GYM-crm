package com.gymops.branch;

import com.gymops.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Assignment of a staff user (manager / receptionist / trainer) to a branch they may operate in. */
@Getter
@Entity
@Table(name = "staff_branch_assignments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StaffAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private GymBranch branch;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by")
    private Long assignedBy;

    public StaffAssignment(User user, GymBranch branch, Instant assignedAt, Long assignedBy) {
        this.user = user;
        this.branch = branch;
        this.assignedAt = assignedAt;
        this.assignedBy = assignedBy;
    }
}
