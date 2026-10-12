package com.gymops.growth.campaign;

import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.growth.campaign.ChurnRiskSource.ChurnRiskUnavailableException;
import com.gymops.growth.campaign.SegmentQueryBuilder.SegmentQuery;
import com.gymops.growth.campaign.SegmentQueryBuilder.SegmentSpec;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves a segment to its current audience (SQL for most segments; analytics + SQL for churn risk). */
@Component
@RequiredArgsConstructor
public class AudienceResolver {

    private final NamedParameterJdbcTemplate jdbc;
    private final ChurnRiskSource churnRisk;
    private final Clock clock;

    public record AudienceMember(Long memberId, String firstName, String lastName, String memberCode, Long branchId,
                                 String branchName, String planName, LocalDate endDate) {
        public String fullName() {
            return firstName + " " + lastName;
        }
    }

    public record Audience(List<AudienceMember> members, boolean truncated) {
    }

    /**
     * @throws ApiException 503 DEPENDENCY_UNAVAILABLE when the churn-risk segment cannot reach the
     *                      analytics service (all other segments are pure SQL and unaffected)
     */
    public Audience resolve(SegmentSpec spec, int limit) {
        List<Long> churnIds = null;
        if (spec.segment() == CampaignSegment.CHURN_RISK) {
            try {
                churnIds = churnRisk.membersAtRisk(spec.churnBand(), spec.branchId(), limit + 1);
            } catch (ChurnRiskUnavailableException e) {
                throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Churn-risk scores are unavailable right now "
                        + "(the analytics service did not respond). Other segments still work; try again later.");
            }
        }
        SegmentQuery q = SegmentQueryBuilder.build(spec, LocalDate.now(clock), clock.getZone(), churnIds);
        var params = new HashMap<>(q.params());
        params.put("limit", limit + 1);
        List<AudienceMember> rows = jdbc.query(q.sql() + " order by x.end_date nulls last, m.id limit :limit", params,
                (rs, n) -> new AudienceMember(rs.getLong("member_id"), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getString("member_code"), rs.getLong("branch_id"),
                        rs.getString("branch_name"), rs.getString("plan_name"),
                        rs.getObject("end_date", LocalDate.class)));
        boolean truncated = rows.size() > limit;
        return new Audience(truncated ? rows.subList(0, limit) : rows, truncated);
    }
}
