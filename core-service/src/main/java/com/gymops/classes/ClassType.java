package com.gymops.classes;

import com.gymops.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A kind of group class in the catalogue (e.g. "Power Yoga"); sessions are scheduled instances of it. */
@Getter
@Entity
@Table(name = "class_types")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassType extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClassCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClassIntensity intensity;

    @Column(name = "default_duration_min", nullable = false)
    private int defaultDurationMin;

    @Column(name = "default_capacity", nullable = false)
    private int defaultCapacity;

    @Column(nullable = false)
    private boolean active = true;

    public ClassType(String name, String description, ClassCategory category, ClassIntensity intensity,
                     int defaultDurationMin, int defaultCapacity) {
        update(name, description, category, intensity, defaultDurationMin, defaultCapacity, true);
    }

    public void update(String name, String description, ClassCategory category, ClassIntensity intensity,
                       int defaultDurationMin, int defaultCapacity, boolean active) {
        this.name = name.trim();
        this.description = description;
        this.category = category;
        this.intensity = intensity;
        this.defaultDurationMin = defaultDurationMin;
        this.defaultCapacity = defaultCapacity;
        this.active = active;
    }
}
