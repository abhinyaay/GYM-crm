package com.gymops.payment.method;

import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.member.Member;
import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A card or UPI handle saved with the payment provider. GymOps keeps only the provider token and
 * display metadata (brand, last 4, masked VPA, expiry), never the card number or UPI PIN.
 */
@Getter
@Entity
@Table(name = "saved_payment_methods")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedPaymentMethod extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod type;

    @Column(name = "provider_customer_id")
    private String providerCustomerId;

    @Column(name = "provider_token", nullable = false)
    private String providerToken;

    private String brand;

    private String last4;

    @Column(name = "exp_month")
    private Short expMonth;

    @Column(name = "exp_year")
    private Short expYear;

    @Column(name = "vpa_masked")
    private String vpaMasked;

    @Column(nullable = false)
    private String label;

    @Column(name = "is_default", nullable = false)
    private boolean defaultMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SavedMethodStatus status = SavedMethodStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "mandate_status", nullable = false)
    private MandateStatus mandateStatus = MandateStatus.NONE;

    @Column(name = "mandate_max_amount")
    private BigDecimal mandateMaxAmount;

    @Column(name = "mandate_expires_at")
    private LocalDate mandateExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public SavedPaymentMethod(Member member, PaymentProvider provider, PaymentMethod type, String providerCustomerId,
                              String providerToken, String brand, String last4, Integer expMonth, Integer expYear,
                              String vpaMasked) {
        if (type != PaymentMethod.CARD && type != PaymentMethod.UPI) {
            throw new BusinessRuleException("Only cards and UPI handles can be saved");
        }
        this.member = member;
        this.provider = provider;
        this.type = type;
        this.providerCustomerId = providerCustomerId;
        this.providerToken = providerToken;
        this.brand = brand;
        this.last4 = last4;
        this.expMonth = expMonth == null ? null : expMonth.shortValue();
        this.expYear = expYear == null ? null : expYear.shortValue();
        this.vpaMasked = vpaMasked;
        this.label = type == PaymentMethod.CARD
                ? (brand == null ? "Card" : brand) + " •••• " + (last4 == null ? "????" : last4)
                : "UPI " + (vpaMasked == null ? "autopay" : vpaMasked);
    }

    public boolean isActive() {
        return status == SavedMethodStatus.ACTIVE;
    }

    /** Usable for merchant-initiated (autopay) debits up to the mandate limit. */
    public boolean canAutoDebit(BigDecimal amount, LocalDate today) {
        return isActive() && mandateStatus == MandateStatus.ACTIVE && !isExpired(today)
                && (mandateMaxAmount == null || amount.compareTo(mandateMaxAmount) <= 0)
                && (mandateExpiresAt == null || !mandateExpiresAt.isBefore(today));
    }

    public boolean isExpired(LocalDate today) {
        return expYear != null && expMonth != null
                && YearMonth.of(expYear, expMonth).isBefore(YearMonth.from(today));
    }

    public void activateMandate(BigDecimal maxAmount, LocalDate expiresAt) {
        this.mandateStatus = MandateStatus.ACTIVE;
        this.mandateMaxAmount = maxAmount;
        this.mandateExpiresAt = expiresAt;
    }

    public void updateMandate(MandateStatus newStatus) {
        if (mandateStatus == MandateStatus.NONE && newStatus != MandateStatus.NONE) {
            return;   // provider event for a token we never set up for recurring use
        }
        this.mandateStatus = newStatus;
    }

    public void makeDefault(boolean value) {
        this.defaultMethod = value;
    }

    public void revoke(Instant now) {
        this.status = SavedMethodStatus.REVOKED;
        this.defaultMethod = false;
        this.revokedAt = now;
        if (mandateStatus != MandateStatus.NONE) {
            mandateStatus = MandateStatus.REVOKED;
        }
    }

    public void markExpired() {
        this.status = SavedMethodStatus.EXPIRED;
        this.defaultMethod = false;
    }
}
