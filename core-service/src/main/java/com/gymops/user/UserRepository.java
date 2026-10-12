package com.gymops.user;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @Query("select count(u) > 0 from User u join u.roles r where r.name = :role")
    boolean existsWithRole(@Param("role") RoleName role);

    @Query("""
            select distinct u from User u left join u.roles r
            where (:role is null or r.name = :role)
              and (:status is null or u.status = :status)
              and (:q is null or lower(u.email) like :q or lower(u.fullName) like :q)
            """)
    Page<User> search(@Param("role") RoleName role, @Param("status") UserStatus status,
                      @Param("q") String q, Pageable pageable);
}
