package com.gymops.notification.channel;

/** The provider rejected the message for a reason a retry cannot fix (e.g. 4xx validation error): fail now. */
public class PermanentDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PermanentDeliveryException(String message) {
        super(message);
    }

    public PermanentDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
