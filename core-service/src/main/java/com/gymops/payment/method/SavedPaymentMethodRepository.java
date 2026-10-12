package com.gymops.payment.method;

import com.gymops.payment.PaymentProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedPaymentMethodRepository extends JpaRepository<SavedPaymentMethod, Long> {

    @Query("""
            select m from SavedPaymentMethod m where m.member.id = :memberId and m.status = 'ACTIVE'
            order by m.defaultMethod desc, m.createdAt desc
            """)
    List<SavedPaymentMethod> findActiveByMember(@Param("memberId") Long memberId);

    Optional<SavedPaymentMethod> findByProviderAndProviderToken(PaymentProvider provider, String providerToken);

    Optional<SavedPaymentMethod> findFirstByProviderToken(String providerToken);

    @Query("""
            select m.providerCustomerId from SavedPaymentMethod m
            where m.member.id = :memberId and m.provider = :provider and m.providerCustomerId is not null
            order by m.createdAt desc limit 1
            """)
    Optional<String> findCustomerId(@Param("memberId") Long memberId, @Param("provider") PaymentProvider provider);
}
