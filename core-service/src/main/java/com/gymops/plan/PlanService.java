package com.gymops.plan;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.config.AppProperties;
import com.gymops.config.CacheConfig;
import com.gymops.plan.dto.PlanRequest;
import com.gymops.plan.dto.PlanResponse;
import com.gymops.pricing.PriceChangeService;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan catalogue. Catalogue reads (GET /plans, /plans/{id}) are served from Redis: they are the
 * most frequent reads and plans change rarely. Every write evicts the affected entries after commit.
 */
@Service
@RequiredArgsConstructor
public class PlanService {

    private final MembershipPlanRepository repository;
    private final AuditService audit;
    private final AppProperties props;
    private final PriceChangeService priceChanges;

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.ACTIVE_PLANS, allEntries = true)
    public PlanResponse create(PlanRequest request) {
        if (repository.existsByCodeIgnoreCase(request.code())) {
            throw new ConflictException("A plan with code " + request.code() + " already exists");
        }
        MembershipPlan plan = new MembershipPlan(request.code());
        plan.setCurrency(props.currency());
        apply(plan, request);
        repository.save(plan);
        audit.record(AuditEvent.of("PLAN", plan.getId(), "CREATED").with("code", plan.getCode())
                .with("price", plan.getPrice()));
        return PlanResponse.from(plan);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PLANS, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.ACTIVE_PLANS, allEntries = true)})
    public PlanResponse update(Long id, PlanRequest request) {
        MembershipPlan plan = load(id);
        if (!plan.getCode().equalsIgnoreCase(request.code()) && repository.existsByCodeIgnoreCase(request.code())) {
            throw new ConflictException("A plan with code " + request.code() + " already exists");
        }
        BigDecimal oldPrice = plan.getPrice();
        BigDecimal oldJoiningFee = plan.getJoiningFee();
        priceChanges.assertDirectEditAllowed(plan, request.price(),
                request.joiningFee() == null ? BigDecimal.ZERO : request.joiningFee());
        plan.setCode(request.code().trim().toUpperCase());
        apply(plan, request);
        // Existing subscriptions keep their own price snapshot; renewals are priced by PricingService.
        priceChanges.recordDirectEdit(plan, oldPrice, oldJoiningFee, request.priceChangeReason());
        audit.record(AuditEvent.of("PLAN", id, "UPDATED").with("oldPrice", oldPrice).with("newPrice", plan.getPrice()));
        return PlanResponse.from(plan);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PLANS, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.ACTIVE_PLANS, allEntries = true)})
    public PlanResponse changeStatus(Long id, PlanStatus status) {
        MembershipPlan plan = load(id);
        PlanStatus previous = plan.getStatus();
        switch (status) {
            case ACTIVE -> plan.activate();
            case ARCHIVED -> plan.archive();
            case DRAFT -> throw new ConflictException("A plan cannot be moved back to DRAFT");
        }
        audit.record(AuditEvent.of("PLAN", id, "STATUS_CHANGED").with("from", previous).with("to", status));
        return PlanResponse.from(plan);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.PLANS, key = "#id")
    public PlanResponse get(Long id) {
        return PlanResponse.from(load(id));
    }

    /** Public catalogue view: non-staff callers may only see ACTIVE plans. */
    public PlanResponse getVisible(PlanResponse plan) {
        boolean staff = CurrentUser.find().map(UserPrincipal::isStaff).orElse(false);
        if (plan.status() != PlanStatus.ACTIVE && !staff) {
            throw new ResourceNotFoundException("Plan", plan.id());
        }
        return plan;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.ACTIVE_PLANS, key = "'all'")
    public List<PlanResponse> listActive() {
        return repository.findByStatusOrderByPriceAsc(PlanStatus.ACTIVE).stream().map(PlanResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanResponse> listAll(PlanStatus status, Pageable pageable) {
        var page = status == null ? repository.findAll(pageable) : repository.findByStatus(status, pageable);
        return PageResponse.of(page, PlanResponse::from);
    }

    public MembershipPlan load(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Plan", id));
    }

    private static void apply(MembershipPlan plan, PlanRequest r) {
        plan.setName(r.name());
        plan.setDescription(r.description());
        plan.setBillingPeriod(r.billingPeriod());
        if (r.billingPeriod() == BillingPeriod.CUSTOM) {
            plan.setDurationDays(r.customDurationDays());
            plan.setDurationMonths(null);
        } else {
            plan.setDurationMonths(r.billingPeriod().months());
            plan.setDurationDays(null);
        }
        plan.setPrice(r.price());
        plan.setJoiningFee(r.joiningFee() == null ? BigDecimal.ZERO : r.joiningFee());
        plan.setAllBranchAccess(r.allBranchAccess());
        plan.setAccessStartTime(r.accessStartTime());
        plan.setAccessEndTime(r.accessEndTime());
        plan.setMaxVisitsPerMonth(r.maxVisitsPerMonth());
        plan.setMaxEntriesPerDay(r.maxEntriesPerDay());
        plan.setClassCreditsPerMonth(r.classCreditsPerMonth());
        plan.setFreezeAllowed(r.freezeAllowed());
        plan.setMinFreezeDays(r.freezeAllowed() ? r.minFreezeDays() : 0);
        plan.setMaxFreezeDays(r.freezeAllowed() ? r.maxFreezeDays() : 0);
        plan.setMaxFreezeCount(r.freezeAllowed() ? r.maxFreezeCount() : 0);
        plan.setCancellationPolicy(r.cancellationPolicy());
        plan.setCoolingOffDays(r.coolingOffDays());
        plan.setCancellationFee(r.cancellationFee() == null ? BigDecimal.ZERO : r.cancellationFee());
        plan.setGracePeriodDays(r.gracePeriodDays());
        plan.replaceBenefits(r.benefits());
    }
}
