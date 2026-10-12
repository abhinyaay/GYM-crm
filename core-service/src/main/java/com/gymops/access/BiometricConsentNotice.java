package com.gymops.access;

import java.nio.charset.StandardCharsets;

/**
 * The consent notice shown to a member before biometric enrolment (DPDP Act 2023, s. 5-6: notice in clear and plain
 * language, specific purpose, free and unambiguous consent, withdrawal as easy as giving it). The version and SHA-256
 * of the exact text are recorded with every consent. Change the text => bump the version.
 */
public final class BiometricConsentNotice {

    public static final String VERSION = "v1";

    public static final String TEXT = """
            Biometric entry: consent notice

            Purpose. With your consent, your fingerprint or face is used only to verify your identity at the entry and \
            exit gates of GymOps branches. It is not used for any other purpose.

            What is processed and where. The gate device captures your fingerprint or face and stores a mathematical \
            template of it on the device itself. Matching happens on the device. GymOps servers never receive or store \
            your fingerprint, face image or template; they store only a reference number that the device assigns to \
            your template, together with this consent record.

            Your choice. Biometric entry is optional. You can always use an RFID card or the rotating QR pass in the \
            member app instead, with the same access.

            Withdrawal. You can withdraw this consent at any time in the member app (Entry pass) or at the front desk. \
            On withdrawal your biometric credentials are revoked immediately and deletion commands are sent to the gate \
            devices to erase your template. Templates are also erased when your member record is deleted.

            Questions or grievances: contact the branch manager or the GymOps grievance officer at the front desk.
            """;

    public static final String SHA256 = AccessKeys.sha256Hex(TEXT.getBytes(StandardCharsets.UTF_8));

    private BiometricConsentNotice() {
    }
}
