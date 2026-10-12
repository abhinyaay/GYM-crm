package com.gymops.classes;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClassTypeRepository extends JpaRepository<ClassType, Long> {

    @Query("select count(t) > 0 from ClassType t where lower(t.name) = lower(:name) and (:excludeId is null or t.id <> :excludeId)")
    boolean existsByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    List<ClassType> findAllByOrderByNameAsc();

    List<ClassType> findByActiveTrueOrderByNameAsc();
}
