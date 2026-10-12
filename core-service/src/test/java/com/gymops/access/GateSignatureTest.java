package com.gymops.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GateSignatureTest {

    private static final String SECRET = "a".repeat(64);
    private static final byte[] BODY = "{\"credentialType\":\"RFID_CARD\"}".getBytes(StandardCharsets.UTF_8);
    private static final String PATH = "/api/v1/access/gate/events";

    @Test
    void canonicalStringCoversMethodPathTimestampNonceAndBodyHash() {
        String canonical = GateSignature.canonical("post", PATH, "1760000000", "nonce-0123456789abcdef", BODY);
        assertThat(canonical).isEqualTo("POST|" + PATH + "|1760000000|nonce-0123456789abcdef|" + AccessKeys.sha256Hex(BODY));
        // empty body hashes to SHA-256("")
        assertThat(GateSignature.canonical("GET", PATH, "1", "n", null))
                .endsWith("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void signatureVerifiesAndAnyChangeBreaksIt() {
        String sig = GateSignature.sign(SECRET, "POST", PATH, "1760000000", "nonce-0123456789abcdef", BODY);
        assertThat(sig).hasSize(64);
        assertThat(GateSignature.verify(SECRET, "POST", PATH, "1760000000", "nonce-0123456789abcdef", BODY, sig)).isTrue();
        assertThat(GateSignature.verify(SECRET, "POST", PATH, "1760000000", "nonce-0123456789abcdef", BODY, sig.toUpperCase()))
                .isTrue();
        assertThat(GateSignature.verify(SECRET, "GET", PATH, "1760000000", "nonce-0123456789abcdef", BODY, sig)).isFalse();
        assertThat(GateSignature.verify(SECRET, "POST", PATH + "/batch", "1760000000", "nonce-0123456789abcdef", BODY, sig))
                .isFalse();
        assertThat(GateSignature.verify(SECRET, "POST", PATH, "1760000001", "nonce-0123456789abcdef", BODY, sig)).isFalse();
        assertThat(GateSignature.verify(SECRET, "POST", PATH, "1760000000", "nonce-0123456789abcdeX", BODY, sig)).isFalse();
        assertThat(GateSignature.verify(SECRET, "POST", PATH, "1760000000", "nonce-0123456789abcdef",
                "{}".getBytes(StandardCharsets.UTF_8), sig)).isFalse();
        assertThat(GateSignature.verify("b".repeat(64), "POST", PATH, "1760000000", "nonce-0123456789abcdef", BODY, sig))
                .isFalse();
        assertThat(GateSignature.verify(SECRET, "POST", PATH, "1760000000", "nonce-0123456789abcdef", BODY, "zz")).isFalse();
        assertThat(GateSignature.verify(SECRET, "POST", PATH, "1760000000", "nonce-0123456789abcdef", BODY, null)).isFalse();
    }

    @Test
    void clockSkewWindowIsInclusiveAndSymmetric() {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");
        Duration skew = Duration.ofMinutes(5);
        assertThat(GateSignature.withinSkew(now.minus(skew), now, skew)).isTrue();
        assertThat(GateSignature.withinSkew(now.plus(skew), now, skew)).isTrue();
        assertThat(GateSignature.withinSkew(now.minus(skew).minusSeconds(1), now, skew)).isFalse();
        assertThat(GateSignature.withinSkew(now.plus(skew).plusSeconds(1), now, skew)).isFalse();
        assertThat(GateSignature.withinSkew(null, now, skew)).isFalse();
    }

    @Test
    void timestampAndNonceFormatsAreStrict() {
        assertThat(GateSignature.parseTimestamp("1760000000")).isEqualTo(Instant.ofEpochSecond(1_760_000_000L));
        assertThat(GateSignature.parseTimestamp("1760000000000000")).isNull();   // milliseconds / garbage
        assertThat(GateSignature.parseTimestamp("-5")).isNull();
        assertThat(GateSignature.parseTimestamp("2026-10-13T10:00:00Z")).isNull();
        assertThat(GateSignature.validNonce("0123456789abcdef")).isTrue();
        assertThat(GateSignature.validNonce("short")).isFalse();
        assertThat(GateSignature.validNonce("has space 0123456789")).isFalse();
        assertThat(GateSignature.validNonce("x".repeat(65))).isFalse();
    }

    @Test
    void responseSignatureIsBoundToTheRequestNonce() {
        byte[] body = "{\"decision\":\"GRANTED\"}".getBytes(StandardCharsets.UTF_8);
        String a = GateSignature.signResponse(SECRET, "nonce-aaaaaaaaaaaaaaaa", body);
        assertThat(a).isEqualTo(AccessKeys.hmacHex(SECRET, "response|nonce-aaaaaaaaaaaaaaaa|" + AccessKeys.sha256Hex(body)));
        assertThat(GateSignature.signResponse(SECRET, "nonce-bbbbbbbbbbbbbbbb", body)).isNotEqualTo(a);
    }

    @Test
    void deviceSecretsAreDerivedPerDeviceAndKeyVersion() {
        AccessKeys keys = new AccessKeys("k".repeat(40));
        String v1 = keys.deviceSecret("gd_abc", 1);
        assertThat(v1).hasSize(64).isEqualTo(keys.deviceSecret("gd_abc", 1));
        assertThat(keys.deviceSecret("gd_abc", 2)).isNotEqualTo(v1);
        assertThat(keys.deviceSecret("gd_abd", 1)).isNotEqualTo(v1);
        assertThat(new AccessKeys("z".repeat(40)).deviceSecret("gd_abc", 1)).isNotEqualTo(v1);
    }

    @Test
    void masterKeyIsRequiredInProductionAndMustBeLongEnough() {
        assertThatThrownBy(() -> AccessKeys.resolve(null, true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCESS_MASTER_KEY");
        assertThatThrownBy(() -> AccessKeys.resolve(" ", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AccessKeys.resolve("too-short", false)).isInstanceOf(IllegalStateException.class);
        assertThat(AccessKeys.resolve(null, false)).isEqualTo(AccessKeys.DEV_MASTER_KEY);
        assertThat(AccessKeys.resolve("p".repeat(32), true)).isEqualTo("p".repeat(32));
    }
}
