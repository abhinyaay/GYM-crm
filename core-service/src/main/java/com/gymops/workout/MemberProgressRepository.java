package com.gymops.workout;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberProgressRepository extends JpaRepository<MemberProgress, Long> {

    Page<MemberProgress> findByMemberIdOrderByRecordedAtDesc(Long memberId, Pageable pageable);

    Optional<MemberProgress> findFirstByMemberIdOrderByRecordedAtAsc(Long memberId);

    Optional<MemberProgress> findFirstByMemberIdOrderByRecordedAtDesc(Long memberId);

    List<MemberProgress> findTop2ByMemberIdOrderByRecordedAtDesc(Long memberId);
}
