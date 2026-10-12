package com.gymops.member;

import com.gymops.subscription.Subscription;
import com.gymops.subscription.SubscriptionStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Composable, index-friendly filters for member search. */
public final class MemberSpecifications {

    private MemberSpecifications() {
    }

    public record Filter(String query, Collection<Long> branchIds, MemberStatus status, MembershipState membership,
                         Instant registeredFrom, Instant registeredTo, LocalDate today) {
    }

    public static Specification<Member> matching(Filter f) {
        return (root, cq, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.branchIds() != null) {
                p.add(root.get("homeBranch").get("id").in(f.branchIds()));
            }
            if (f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.query() != null && !f.query().isBlank()) {
                String q = f.query().trim().toLowerCase();
                String like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(
                        cb.equal(cb.lower(root.get("memberCode")), q),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(root.get("phone"), like),
                        cb.like(cb.lower(root.get("firstName")), like),
                        cb.like(cb.lower(root.get("lastName")), like)));
            }
            if (f.registeredFrom() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("registeredAt"), f.registeredFrom()));
            }
            if (f.registeredTo() != null) {
                p.add(cb.lessThan(root.get("registeredAt"), f.registeredTo()));
            }
            if (f.membership() != null) {
                Subquery<Long> sq = cq.subquery(Long.class);
                Root<Subscription> s = sq.from(Subscription.class);
                sq.select(s.get("id"));
                List<Predicate> sp = new ArrayList<>();
                sp.add(cb.equal(s.get("member"), root));
                switch (f.membership()) {
                    case ACTIVE, GRACE, FROZEN -> sp.add(cb.equal(s.get("status"),
                            SubscriptionStatus.valueOf(f.membership().name())));
                    case SCHEDULED -> sp.add(cb.equal(s.get("status"), SubscriptionStatus.SCHEDULED));
                    case PENDING_PAYMENT -> sp.add(cb.equal(s.get("status"), SubscriptionStatus.PENDING_PAYMENT));
                    case NONE -> sp.add(s.get("status").in(EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.GRACE,
                            SubscriptionStatus.FROZEN, SubscriptionStatus.SCHEDULED)));
                }
                sq.where(sp.toArray(Predicate[]::new));
                p.add(f.membership() == MembershipState.NONE ? cb.not(cb.exists(sq)) : cb.exists(sq));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}
