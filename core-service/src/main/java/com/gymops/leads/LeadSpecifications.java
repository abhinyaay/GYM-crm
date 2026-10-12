package com.gymops.leads;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/** Dynamic search filters for the lead list. */
public final class LeadSpecifications {

    /**
     * @param branchIds        permitted branches, or null for unrestricted
     * @param followUpDueBefore when set, only open leads whose next follow-up falls before this instant
     */
    public record Filter(Set<Long> branchIds, Set<LeadStatus> statuses, LeadSource source, Long ownerUserId,
                         boolean unassigned, String query, Instant followUpDueBefore, Instant createdFrom,
                         Instant createdBefore) {
    }

    private LeadSpecifications() {
    }

    public static Specification<Lead> matching(Filter f) {
        return (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.branchIds() != null) {
                p.add(root.get("branch").get("id").in(f.branchIds()));
            }
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                p.add(root.get("status").in(f.statuses()));
            }
            if (f.source() != null) {
                p.add(cb.equal(root.get("source"), f.source()));
            }
            if (f.unassigned()) {
                p.add(cb.isNull(root.get("ownerUserId")));
            } else if (f.ownerUserId() != null) {
                p.add(cb.equal(root.get("ownerUserId"), f.ownerUserId()));
            }
            if (f.query() != null && !f.query().isBlank()) {
                String term = f.query().trim().toLowerCase(Locale.ROOT);
                String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(cb.concat(cb.concat(root.get("firstName"), " "),
                        cb.coalesce(root.get("lastName"), ""))), like, '\\'));
                any.add(cb.like(cb.lower(cb.coalesce(root.get("email"), "")), like, '\\'));
                String digits = term.replaceAll("\\D", "");
                if (digits.length() >= 3) {
                    any.add(cb.like(root.get("phone"), "%" + digits + "%"));
                }
                p.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (f.followUpDueBefore() != null) {
                p.add(root.get("status").in(LeadStatus.OPEN));
                p.add(cb.lessThan(root.get("nextFollowUpAt"), f.followUpDueBefore()));
            }
            if (f.createdFrom() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.createdFrom()));
            }
            if (f.createdBefore() != null) {
                p.add(cb.lessThan(root.get("createdAt"), f.createdBefore()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}
