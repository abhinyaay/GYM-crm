package com.gymops.payment;

import java.util.EnumSet;
import java.util.Set;

public enum PaymentMethod {
    CASH,
    CARD,
    UPI,
    BANK_TRANSFER,
    NET_BANKING,
    WALLET,
    EMI,
    STORE_CREDIT;

    /** Recorded by staff at the desk; no gateway involved. */
    public static final Set<PaymentMethod> OFFLINE = EnumSet.of(CASH, BANK_TRANSFER);

    /** Paid through the payment provider's hosted checkout. */
    public static final Set<PaymentMethod> ONLINE = EnumSet.of(CARD, UPI, NET_BANKING, WALLET, EMI);

    /** Instruments that can be saved as a token and used for one-click payments and autopay. */
    public static final Set<PaymentMethod> TOKENISABLE = EnumSet.of(CARD, UPI);
}
