package com.gymops.growth.referral;

import com.gymops.member.Member;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stable per-member referral codes, generated on first use. {@code INSERT ... ON CONFLICT DO NOTHING}
 * covers both races (two first requests for the same member, or two members drawing the same code):
 * the loser re-reads the member's code or draws again with a longer suffix.
 */
@Service
@RequiredArgsConstructor
public class ReferralCodeService {

    private static final int ATTEMPTS = 15;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public String codeFor(Member member) {
        Optional<String> existing = find(member.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String code = ReferralRules.generateCode(member.getFirstName(), 3 + attempt / 5, random);
            int inserted = jdbc.update("insert into referral_codes (member_id, code, created_at) values (?, ?, ?) "
                    + "on conflict do nothing", member.getId(), code, Timestamp.from(Instant.now(clock)));
            if (inserted == 1) {
                return code;
            }
            existing = find(member.getId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        throw new IllegalStateException("Could not allocate a referral code for member " + member.getId());
    }

    @Transactional(readOnly = true)
    public Optional<String> find(Long memberId) {
        List<String> codes = jdbc.queryForList("select code from referral_codes where member_id = ?", String.class, memberId);
        return codes.stream().findFirst();
    }

    /** Member id owning a (normalised) code. */
    @Transactional(readOnly = true)
    public Optional<Long> ownerOf(String code) {
        return ReferralRules.normalizeCode(code).flatMap(c -> jdbc.queryForList(
                "select member_id from referral_codes where code = ?", Long.class, c).stream().findFirst());
    }
}
