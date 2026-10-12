package com.gymops.notification.sms;

import lombok.extern.slf4j.Slf4j;

/** Development / demo SMS provider: logs a masked number and the segment count instead of sending. */
@Slf4j
public class LogSmsGateway implements SmsGateway {

    @Override
    public String name() {
        return "log";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public SmsResult send(SmsMessage m) {
        int segments = SmsSegments.count(m.text());
        log.info("notification_sms id={} type={} to={} segments={} chars={}", m.notificationId(), m.type(),
                PhoneNumbers.mask(m.to()), segments, m.text().length());
        return new SmsResult("log-" + m.notificationId(), segments);
    }
}
