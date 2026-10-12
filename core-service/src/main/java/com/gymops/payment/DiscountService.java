package com.gymops.payment;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.payment.dto.DiscountRequest;
import com.gymops.payment.dto.DiscountResponse;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DiscountService {

    private final DiscountRepository repository;
    private final AuditService audit;

    @Transactional
    public DiscountResponse create(DiscountRequest r) {
        if (repository.findByCode(r.code()).isPresent()) {
            throw new ConflictException("Discount code " + r.code() + " already exists");
        }
        Discount d = new Discount(r.code(), r.discountType(), r.value(), r.validFrom());
        d.setDescription(r.description());
        d.setValidUntil(r.validUntil());
        d.setMaxRedemptions(r.maxRedemptions());
        repository.save(d);
        audit.record(AuditEvent.of("DISCOUNT", d.getId(), "CREATED").with("code", d.getCode()));
        return DiscountResponse.from(d);
    }

    @Transactional
    public DiscountResponse deactivate(Long id) {
        Discount d = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Discount", id));
        d.setActive(false);
        audit.record(AuditEvent.of("DISCOUNT", id, "DEACTIVATED"));
        return DiscountResponse.from(d);
    }

    @Transactional(readOnly = true)
    public PageResponse<DiscountResponse> list(Pageable pageable) {
        return PageResponse.of(repository.findAll(pageable), DiscountResponse::from);
    }

    /**
     * Validates and atomically reserves one redemption of a discount code.
     * Must run inside the transaction that creates the invoice.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<Discount> redeem(String code, LocalDate today) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Discount discount = repository.findByCode(code)
                .orElseThrow(() -> new BusinessRuleException("Discount code " + code + " is not valid"));
        if (!discount.isUsableOn(today) || repository.tryRedeem(discount.getId()) == 0) {
            throw new BusinessRuleException("Discount code " + code + " is expired, inactive or fully redeemed");
        }
        return Optional.of(discount);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void release(Discount discount) {
        repository.release(discount.getId());
    }
}
