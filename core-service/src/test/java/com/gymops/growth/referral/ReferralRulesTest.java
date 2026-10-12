package com.gymops.growth.referral;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.growth.referral.ReferralRules.Party;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReferralRulesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void codesAreNamePrefixPlusUnambiguousSuffix() {
        Random random = new Random(1);
        String code = ReferralRules.generateCode("Asha", 3, random);
        assertThat(code).matches("^ASHA[23456789ABCDEFGHJKMNPQRSTVWXYZ]{3}$");
        assertThat(ReferralRules.generateCode("Siddharth", 3, random)).startsWith("SIDD").hasSize(7);
        assertThat(ReferralRules.generateCode("Jo", 3, random)).startsWith("JO").hasSize(5);
        assertThat(ReferralRules.generateCode("Asha", 5, random)).hasSize(9);
    }

    @Test
    void prefixStripsAccentsAndFallsBackForNonLatinOrShortNames() {
        assertThat(ReferralRules.codePrefix("José")).isEqualTo("JOSE");
        assertThat(ReferralRules.codePrefix("Zoë-Ann")).isEqualTo("ZOEA");
        assertThat(ReferralRules.codePrefix("आशा")).isEqualTo("GYM");
        assertThat(ReferralRules.codePrefix("A")).isEqualTo("GYM");
        assertThat(ReferralRules.codePrefix(null)).isEqualTo("GYM");
    }

    @Test
    void generatedCodesNeverContainLookAlikesAndRarelyCollide() {
        Random random = new Random(42);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = ReferralRules.generateCode("Asha", 4, random);
            assertThat(code.substring(4)).doesNotContain("0", "O", "1", "I", "L", "U");
            seen.add(code);
        }
        assertThat(seen.size()).isGreaterThan(1990);
    }

    @Test
    void normalizesUserTypedCodes() {
        assertThat(ReferralRules.normalizeCode(" asha-7k2 ")).contains("ASHA7K2");
        assertThat(ReferralRules.normalizeCode("ASHA 7K2")).contains("ASHA7K2");
        assertThat(ReferralRules.normalizeCode("AB")).isEmpty();
        assertThat(ReferralRules.normalizeCode("ASHA7K2'; drop")).isEmpty();
        assertThat(ReferralRules.normalizeCode(null)).isEmpty();
    }

    @Test
    void selfReferralBySameMemberEmailOrPhone() {
        Party referrer = new Party(1L, "Asha@Example.com", "+91 98765 43210");
        assertThat(ReferralRules.isSelfReferral(referrer, new Party(1L, "other@example.com", "+91 90000 00000"))).isTrue();
        assertThat(ReferralRules.isSelfReferral(referrer, new Party(2L, " asha@example.com", null))).isTrue();
        assertThat(ReferralRules.isSelfReferral(referrer, new Party(null, null, "9876543210"))).isTrue();
        assertThat(ReferralRules.isSelfReferral(referrer, new Party(null, null, "+919876543210"))).isTrue();
        assertThat(ReferralRules.isSelfReferral(referrer, new Party(2L, "rahul@example.com", "+91 91234 56789"))).isFalse();
        assertThat(ReferralRules.isSelfReferral(new Party(1L, null, null), new Party(null, null, null))).isFalse();
    }

    @Test
    void refereeMustBeNewAllowingTheDeskGraceWindow() {
        Instant referral = Instant.parse("2026-10-10T10:00:00Z");
        assertThat(ReferralRules.isNewCustomer(null, referral, 7)).isTrue();
        assertThat(ReferralRules.isNewCustomer(referral.plusSeconds(3600), referral, 7)).as("bought after the referral").isTrue();
        assertThat(ReferralRules.isNewCustomer(referral.minusSeconds(6 * 86400), referral, 7)).as("inside grace").isTrue();
        assertThat(ReferralRules.isNewCustomer(referral.minusSeconds(8 * 86400), referral, 7)).as("old customer").isFalse();
        assertThat(ReferralRules.isNewCustomer(referral.minusSeconds(60), referral, 0)).isFalse();
    }

    @Test
    void monthlyCapAndBusinessTimezoneMonthWindow() {
        assertThat(ReferralRules.capReached(4, 5)).isFalse();
        assertThat(ReferralRules.capReached(5, 5)).isTrue();
        // 31 Oct 20:00 UTC is already 1 Nov in India
        Instant[] window = ReferralRules.monthWindow(Instant.parse("2026-10-31T20:00:00Z"), IST);
        assertThat(window[0]).isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
        assertThat(window[1]).isEqualTo(Instant.parse("2026-11-30T18:30:00Z"));
    }

    @Test
    void holdAndDisplayName() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        assertThat(ReferralRules.holdUntil(now, 14)).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(ReferralRules.displayName("Rahul", "sharma")).isEqualTo("Rahul S.");
        assertThat(ReferralRules.displayName("Rahul", null)).isEqualTo("Rahul");
    }
}
