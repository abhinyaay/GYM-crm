package com.gymops.member;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long>, JpaSpecificationExecutor<Member> {

    @Query("select count(m) > 0 from Member m where lower(m.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    Optional<Member> findByMemberCode(String memberCode);

    Optional<Member> findByUserId(Long userId);

    @Query(value = "select nextval('member_code_seq')", nativeQuery = true)
    long nextMemberCodeValue();

    /**
     * SELECT ... FOR UPDATE on the member row. Every operation that changes a member's
     * subscriptions takes this lock first, which serialises concurrent membership changes for the
     * same member (renew vs. upgrade vs. freeze) without blocking other members.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);
}
