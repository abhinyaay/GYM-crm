package com.gymops.notification.channel;

/**
 * The recipient cannot be reached on this channel (no usable phone, invalid address, recipient replied STOP):
 * the notification is SKIPPED rather than retried or failed.
 */
public class RecipientUnusableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RecipientUnusableException(String message) {
        super(message);
    }
}
