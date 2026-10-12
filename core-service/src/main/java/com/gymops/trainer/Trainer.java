package com.gymops.trainer;

import com.gymops.branch.GymBranch;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Setter
@Entity
@Table(name = "trainers")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trainer extends BaseEntity {

    @Setter(AccessLevel.NONE)
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_branch_id")
    private GymBranch homeBranch;

    @Column(name = "employee_code", nullable = false, unique = true)
    private String employeeCode;

    private String bio;
    private String certifications;

    @Column(name = "years_of_experience", nullable = false)
    private int yearsOfExperience;

    @Column(name = "hourly_rate")
    private BigDecimal hourlyRate;

    @Column(name = "max_active_clients", nullable = false)
    private int maxActiveClients = 20;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrainerStatus status = TrainerStatus.ACTIVE;

    @Setter(AccessLevel.NONE)
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Setter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "trainer_specializations", joinColumns = @JoinColumn(name = "trainer_id"))
    @Column(name = "specialization", nullable = false)
    private Set<String> specializations = new HashSet<>();

    @Setter(AccessLevel.NONE)
    @OrderBy("dayOfWeek ASC, startTime ASC")
    @OneToMany(mappedBy = "trainer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TrainerAvailability> availability = new ArrayList<>();

    public Trainer(User user, GymBranch homeBranch, String employeeCode) {
        this.user = user;
        this.homeBranch = homeBranch;
        this.employeeCode = employeeCode.trim().toUpperCase();
    }

    public boolean isActive() {
        return status == TrainerStatus.ACTIVE;
    }

    public void replaceSpecializations(Set<String> values) {
        specializations.clear();
        if (values != null) {
            values.stream().map(v -> v.trim().toUpperCase()).forEach(specializations::add);
        }
    }

    public void replaceAvailability(List<TrainerAvailability> slots) {
        availability.clear();
        slots.forEach(s -> {
            s.attachTo(this);
            availability.add(s);
        });
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
        this.status = TrainerStatus.INACTIVE;
    }
}
