package com.gymops.workout;

import com.gymops.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "exercises")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Exercise extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "muscle_group", nullable = false)
    private String muscleGroup;

    private String equipment;
    private String description;

    public Exercise(String name, String muscleGroup, String equipment, String description) {
        this.name = name.trim();
        this.muscleGroup = muscleGroup.trim().toUpperCase();
        this.equipment = equipment;
        this.description = description;
    }
}
