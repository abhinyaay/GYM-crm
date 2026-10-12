package com.gymops.access;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiometricConsentRepository extends JpaRepository<BiometricConsent, Long> {

    @Query("select c from BiometricConsent c where c.memberId = :memberId and c.withdrawnAt is null")
    Optional<BiometricConsent> findActive(@Param("memberId") Long memberId);

    List<BiometricConsent> findByMemberIdOrderByGrantedAtDesc(Long memberId);
}
