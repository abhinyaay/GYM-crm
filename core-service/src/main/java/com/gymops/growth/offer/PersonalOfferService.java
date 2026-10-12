package com.gymops.growth.offer;

import com.gymops.payment.DiscountRepository;
import com.gymops.payment.DiscountService;
import com.gymops.payment.DiscountType;
import com.gymops.payment.dto.DiscountRequest;
import com.gymops.payment.dto.DiscountResponse;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues single-use personal discount codes through the discount module: an ordinary
 * {@code discounts} row with {@code max_redemptions = 1} plus a {@code personal_discount_codes}
 * row binding it to one member. A database trigger on {@code invoices} rejects the code on anyone
 * else's invoice (mapped to 422 by {@code DatabaseConstraints}).
 */
@Service
@RequiredArgsConstructor
public class PersonalOfferService {

    /** Unambiguous characters only; 30^8 combinations make codes unguessable in practice. */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int RANDOM_LENGTH = 8;
    private static final int MAX_ATTEMPTS = 8;

    private final DiscountService discountService;
    private final DiscountRepository discountRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public record IssuedOffer(Long discountId, String code, DiscountType type, BigDecimal value, LocalDate validUntil) {
    }

    /** Creates the code inside the caller's transaction (it commits or rolls back with the reward / message). */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedOffer issue(Long memberId, String prefix, DiscountType type, BigDecimal value, int validDays,
                             OfferPurpose purpose, String description) {
        String code = uniqueCode(prefix);
        LocalDate today = LocalDate.now(clock);
        LocalDate until = today.plusDays(validDays - 1L);
        DiscountResponse d = discountService.create(new DiscountRequest(code, truncate(description, 300), type, value,
                today, until, 1));
        jdbc.update("insert into personal_discount_codes (discount_id, member_id, purpose, created_at) values (?, ?, ?, ?)",
                d.id(), memberId, purpose.name(), Timestamp.from(Instant.now(clock)));
        return new IssuedOffer(d.id(), d.code(), type, value, until);
    }

    /** Deactivates a personal code that has not been used yet (e.g. its reward was reversed). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean revokeIfUnused(Long discountId) {
        return discountRepository.findById(discountId)
                .filter(d -> d.isActive() && d.getTimesRedeemed() == 0)
                .map(d -> {
                    discountService.deactivate(discountId);
                    return true;
                }).orElse(false);
    }

    String uniqueCode(String prefix) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < RANDOM_LENGTH; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = sb.toString();
            if (discountRepository.findByCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique discount code");
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
