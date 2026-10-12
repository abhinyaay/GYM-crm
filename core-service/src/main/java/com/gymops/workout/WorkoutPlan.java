package com.gymops.workout;

import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.member.Member;
import com.gymops.trainer.Trainer;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Either a reusable template (member == null) or a member-specific plan copied from a template, so a
 * trainer can personalise a member's plan without changing the template for everyone else.
 */
@Getter
@Setter
@Entity
@Table(name = "workout_plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkoutPlan extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkoutGoal goal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Difficulty difficulty;

    @Column(name = "duration_weeks", nullable = false)
    private int durationWeeks;

    @Setter(AccessLevel.NONE)
    @Column(nullable = false)
    private boolean template;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trainer_id")
    private Trainer trainer;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_template_id")
    private WorkoutPlan sourceTemplate;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkoutPlanStatus status;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "trainer_notes")
    private String trainerNotes;

    @Setter(AccessLevel.NONE)
    @OrderBy("dayNumber ASC, position ASC")
    @OneToMany(mappedBy = "workoutPlan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkoutPlanExercise> exercises = new ArrayList<>();

    public static WorkoutPlan template(Trainer trainer, String name, WorkoutGoal goal, Difficulty difficulty, int weeks) {
        WorkoutPlan p = new WorkoutPlan();
        p.trainer = trainer;
        p.name = name;
        p.goal = goal;
        p.difficulty = difficulty;
        p.durationWeeks = weeks;
        p.template = true;
        p.status = WorkoutPlanStatus.ACTIVE;
        return p;
    }

    /** Personal copy of this template for a member, starting on {@code startDate}. */
    public WorkoutPlan assignTo(Member member, Trainer assigningTrainer, LocalDate startDate) {
        if (!template) {
            throw new InvalidStateTransitionException("workout plan", "MEMBER_PLAN", "assign (only templates can be assigned)");
        }
        WorkoutPlan copy = new WorkoutPlan();
        copy.name = name;
        copy.description = description;
        copy.goal = goal;
        copy.difficulty = difficulty;
        copy.durationWeeks = durationWeeks;
        copy.template = false;
        copy.member = member;
        copy.trainer = assigningTrainer;
        copy.sourceTemplate = this;
        copy.status = WorkoutPlanStatus.ACTIVE;
        copy.startDate = startDate;
        copy.endDate = startDate.plusWeeks(durationWeeks).minusDays(1);
        copy.replaceExercises(exercises.stream().map(WorkoutPlanExercise::copy).toList());
        return copy;
    }

    public void replaceExercises(List<WorkoutPlanExercise> items) {
        exercises.clear();
        items.forEach(e -> {
            e.attachTo(this);
            exercises.add(e);
        });
    }

    public void complete() {
        requireActiveMemberPlan("complete");
        status = WorkoutPlanStatus.COMPLETED;
    }

    public void archive() {
        status = WorkoutPlanStatus.ARCHIVED;
    }

    public boolean isActiveMemberPlan() {
        return !template && status == WorkoutPlanStatus.ACTIVE;
    }

    private void requireActiveMemberPlan(String action) {
        if (!isActiveMemberPlan()) {
            throw new InvalidStateTransitionException("workout plan", status, action);
        }
    }
}
