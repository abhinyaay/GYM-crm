package com.gymops.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

    @ParameterizedTest
    @CsvSource({
            "'+91 98765 43210', +919876543210",
            "9876543210, +919876543210",
            "09876543210, +919876543210",
            "919876543210, +919876543210",
            "0091-98765-43210, +919876543210",
            "(+91) 98765-43210, +919876543210",
            "9123456789, +919123456789",
            "'+1 (212) 555-1234', +12125551234",
            "+447911123456, +447911123456"})
    void normalisesToE164(String raw, String expected) {
        assertThat(PhoneNumbers.toE164(raw, "91")).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "N/A", "12345", "022 2345 6789", "+91 12345 67890", "98765432101234567", "+0123456789"})
    void rejectsNumbersThatCannotReceiveSms(String raw) {
        assertThat(PhoneNumbers.toE164(raw, "91")).isEmpty();
    }

    @Test
    void usesTheConfiguredDefaultCountry() {
        assertThat(PhoneNumbers.toE164("07911 123456", "44")).contains("+447911123456");
        assertThat(PhoneNumbers.toE164("2125551234", "1")).contains("+12125551234");
    }

    @Test
    void masksTheMiddleDigits() {
        assertThat(PhoneNumbers.mask("+919876543210")).isEqualTo("+91******3210");
        assertThat(PhoneNumbers.mask(null)).isEqualTo("***");
    }
}
