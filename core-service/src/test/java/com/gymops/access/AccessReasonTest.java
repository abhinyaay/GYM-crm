package com.gymops.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.attendance.EntryDenial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class AccessReasonTest {

    @ParameterizedTest
    @CsvSource({
        "MEMBER_SUSPENDED, MEMBER_SUSPENDED",
        "MEMBER_INACTIVE, MEMBER_INACTIVE",
        "ALREADY_INSIDE, ANTI_PASSBACK",
        "BRANCH_CLOSED, BRANCH_CLOSED",
        "NO_ACTIVE_MEMBERSHIP, NO_ACTIVE_MEMBERSHIP",
        "MEMBERSHIP_FROZEN, FROZEN",
        "BRANCH_NOT_INCLUDED, BRANCH_NOT_ALLOWED",
        "OUTSIDE_PLAN_HOURS, OUTSIDE_PLAN_HOURS",
        "VISIT_LIMIT, VISIT_LIMIT",
        "AT_CAPACITY, AT_CAPACITY"})
    void sharedEntryRuleDenialsMapToGateReasonCodes(EntryDenial denial, AccessReason expected) {
        assertThat(AccessReason.from(denial)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(EntryDenial.class)
    void everyDenialHasAnErrorCodeAndMetricTag(EntryDenial denial) {
        assertThat(denial.errorCode()).isNotNull();
        assertThat(denial.metricTag()).isNotBlank();
        assertThat(AccessReason.from(denial)).isNotNull();
    }

    @Test
    void deviceDirectionPermitsMatchingScansOnly() {
        assertThat(DeviceDirection.BOTH.permits(Direction.ENTRY)).isTrue();
        assertThat(DeviceDirection.BOTH.permits(Direction.EXIT)).isTrue();
        assertThat(DeviceDirection.ENTRY.permits(Direction.ENTRY)).isTrue();
        assertThat(DeviceDirection.ENTRY.permits(Direction.EXIT)).isFalse();
        assertThat(DeviceDirection.EXIT.permits(Direction.ENTRY)).isFalse();
    }

    @Test
    void cardUidsAreNormalisedBeforeHashing() {
        assertThat(CredentialHashes.normalizeCardUid("04:a1-b2 c3")).isEqualTo("04A1B2C3");
        assertThat(CredentialHashes.normalizeCardUid("xyz")).isNull();
        assertThat(CredentialHashes.cardHash("04A1B2C3")).hasSize(64)
                .isEqualTo(CredentialHashes.cardHash(CredentialHashes.normalizeCardUid("04a1b2c3")));
        assertThat(CredentialHashes.templateHash(CredentialType.FINGERPRINT, "T1"))
                .isNotEqualTo(CredentialHashes.templateHash(CredentialType.FACE, "T1"));
        assertThat(CredentialHashes.hint(CredentialType.RFID_CARD, "04A1B2C3")).isEqualTo("Card …B2C3");
        assertThat(CredentialHashes.normalizeTemplateRef("bad ref")).isNull();
    }
}
