package com.gymops.access;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessCredentialRepository extends JpaRepository<AccessCredential, Long> {

    List<AccessCredential> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    @Query("""
            select c from AccessCredential c
            where c.member.id = :memberId and c.status in :statuses
            order by c.id
            """)
    List<AccessCredential> findByMemberAndStatuses(@Param("memberId") Long memberId,
                                                   @Param("statuses") Collection<CredentialStatus> statuses);

    @Query("""
            select count(c) > 0 from AccessCredential c
            where c.member.id = :memberId and c.status in :statuses
            """)
    boolean existsByMemberAndStatuses(@Param("memberId") Long memberId,
                                      @Param("statuses") Collection<CredentialStatus> statuses);

    /** The live (pending / active / suspended) credential with this hash, if any. */
    @Query("""
            select c from AccessCredential c join fetch c.member
            where c.type = :type and c.credentialHash = :hash
              and c.status <> com.gymops.access.CredentialStatus.REVOKED
            """)
    Optional<AccessCredential> findLiveByHash(@Param("type") CredentialType type, @Param("hash") String hash);

    @Query("""
            select c from AccessCredential c join fetch c.member
            where c.type = :type and c.credentialHash = :hash
              and c.status = com.gymops.access.CredentialStatus.REVOKED
            order by c.revokedAt desc
            """)
    List<AccessCredential> findRevokedByHash(@Param("type") CredentialType type, @Param("hash") String hash);

    @Query("""
            select c from AccessCredential c
            where c.member.id = :memberId and c.type = com.gymops.access.CredentialType.QR
            order by c.id desc
            """)
    List<AccessCredential> findQrPasses(@Param("memberId") Long memberId);

    /** Active card / biometric credentials (offline allowlist candidates). */
    @Query("""
            select c from AccessCredential c join fetch c.member m
            where c.status = com.gymops.access.CredentialStatus.ACTIVE
              and c.type <> com.gymops.access.CredentialType.QR
              and m.status = com.gymops.member.MemberStatus.ACTIVE
            """)
    List<AccessCredential> findActiveForAllowlist();

    /** Active credentials of members whose home branch is in scope (gate simulator support). */
    @Query("""
            select c from AccessCredential c join fetch c.member m
            where c.status = com.gymops.access.CredentialStatus.ACTIVE and c.type in :types
              and m.homeBranch.id = :branchId
            order by m.firstName, m.lastName
            """)
    List<AccessCredential> findActiveAtHomeBranch(@Param("branchId") Long branchId,
                                                  @Param("types") Collection<CredentialType> types);
}
