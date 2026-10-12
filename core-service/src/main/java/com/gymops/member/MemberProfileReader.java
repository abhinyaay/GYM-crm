package com.gymops.member;

import com.gymops.common.event.DomainEvents;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.config.CacheConfig;
import com.gymops.member.dto.MemberResponse;
import com.gymops.subscription.MembershipQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached member-profile read model (front-desk lookups are the hottest read path). Authorization is
 * applied by the caller on the returned value, so the cache never bypasses access control.
 * Entries are evicted on any {@link DomainEvents.MemberChanged}; the cache manager is
 * transaction-aware, so the eviction happens only after the change commits.
 */
@Component
@RequiredArgsConstructor
public class MemberProfileReader {

    private final MemberRepository repository;
    private final MembershipQueryService membershipQuery;
    private final CacheManager cacheManager;

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.MEMBER_PROFILES, key = "#memberId")
    public MemberResponse load(Long memberId) {
        Member member = repository.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        return MemberResponse.from(member, membershipQuery.currentMembership(memberId));
    }

    @EventListener
    public void onMemberChanged(DomainEvents.MemberChanged event) {
        var cache = cacheManager.getCache(CacheConfig.MEMBER_PROFILES);
        if (cache != null) {
            cache.evict(event.memberId());
        }
    }
}
