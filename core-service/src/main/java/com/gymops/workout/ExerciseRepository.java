package com.gymops.workout;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExerciseRepository extends JpaRepository<Exercise, Long> {

    @Query("select count(e) > 0 from Exercise e where lower(e.name) = lower(:name)")
    boolean existsByName(@Param("name") String name);

    @Query("""
            select e from Exercise e
            where (:muscleGroup is null or e.muscleGroup = :muscleGroup)
              and (:q is null or lower(e.name) like :q)
            """)
    Page<Exercise> search(@Param("muscleGroup") String muscleGroup, @Param("q") String q, Pageable pageable);
}
