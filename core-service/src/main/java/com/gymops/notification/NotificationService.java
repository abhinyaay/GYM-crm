package com.gymops.notification;

import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.notification.dto.NotificationResponse;
import com.gymops.notification.preference.MemberPreferences;
import com.gymops.notification.preference.NotificationPreferenceService;
import com.gymops.notification.sms.PhoneNumbers;
import com.gymops.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;
    private final NotificationPreferenceService preferences;
    private final DeliveryPolicy deliveryPolicy;
    private final NotificationProperties props;
    private final Clock clock;

    /**
     * Enqueues a member notification. Joins the caller's transaction (outbox pattern); {@code dedupKey} (optional)
     * makes scheduled reminders idempotent across job re-runs. Channels:
     * <ul>
     *   <li>IN_APP when the member has a login (always);</li>
     *   <li>EMAIL unless the member switched e-mail off for the type's {@link NotificationCategory} (locked
     *       categories such as BILLING are always e-mailed);</li>
     *   <li>SMS for SMS-worthy types ({@link NotificationCategory#smsByDefault}) when the category's SMS toggle is on
     *       and the member's phone normalises to a mobile number; promotional SMS is deferred out of quiet hours.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyMember(Member member, NotificationType type, String title, String body, String dedupKey) {
        notifyMember(member, type, title, body, dedupKey, NotifyOptions.DEFAULTS);
    }

    /** As {@link #notifyMember(Member, NotificationType, String, String, String)}, with channel / SMS-text options. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyMember(Member member, NotificationType type, String title, String body, String dedupKey,
                             NotifyOptions options) {
        NotifyOptions opts = options == null ? NotifyOptions.DEFAULTS : options;
        Instant now = Instant.now(clock);
        Long userId = member.getUser() == null ? null : member.getUser().getId();
        if (userId != null && opts.includes(NotificationChannel.IN_APP)) {
            enqueue(type, NotificationChannel.IN_APP, userId, member.getId(), null, title, body, dedupKey, now);
        }
        MemberPreferences prefs = preferences.forMember(member.getId());
        if (opts.includes(NotificationChannel.EMAIL) && member.getEmail() != null && !member.getEmail().isBlank()
                && prefs.allows(type, NotificationChannel.EMAIL)) {
            enqueue(type, NotificationChannel.EMAIL, userId, member.getId(), member.getEmail(), title, body, dedupKey, now);
        }
        boolean smsWanted = opts.channels() == null ? NotificationCategory.smsByDefault(type)
                : opts.channels().contains(NotificationChannel.SMS);
        if (smsWanted && props.sms().enabled() && prefs.allows(type, NotificationChannel.SMS)) {
            Optional<String> phone = PhoneNumbers.toE164(member.getPhone(), props.sms().defaultCountryCode());
            if (phone.isEmpty()) {
                log.debug("SMS for member {} ({}) skipped: no usable mobile number", member.getId(), type);
                return;
            }
            Instant notBefore = NotificationCategory.of(type).isPromotional() ? deliveryPolicy.quietHours().nextAllowed(now) : now;
            Notification sms = enqueue(type, NotificationChannel.SMS, userId, member.getId(), phone.get(), title,
                    smsText(title, body, opts.smsText()), dedupKey, now);
            if (sms != null) {
                sms.notBefore(notBefore);
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyUsers(Collection<Long> userIds, NotificationType type, String title, String body) {
        Instant now = Instant.now(clock);
        userIds.forEach(id -> enqueue(type, NotificationChannel.IN_APP, id, null, null, title, body, null, now));
    }

    private Notification enqueue(NotificationType type, NotificationChannel channel, Long userId, Long memberId,
                                 String destination, String title, String body, String dedupKey, Instant now) {
        String key = dedupKey == null ? null : dedupKey + ":" + channel;
        if (key != null && repository.existsByDedupKey(key)) {
            return null;
        }
        return repository.save(new Notification(type, channel, userId, memberId, destination, title, body, key, now));
    }

    /** SMS text: explicit text or "title: body", whitespace collapsed and cut to the configured length. */
    String smsText(String title, String body, String explicit) {
        String text = explicit != null && !explicit.isBlank() ? explicit
                : (title == null || title.isBlank() ? "" : title + ": ") + (body == null ? "" : body);
        text = text.replaceAll("\\s+", " ").strip();
        int max = props.sms().maxLength();
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";   // ASCII: keeps GSM-7 encoding
    }

    // ------------------------------------------------------------------ inbox API

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> inbox(boolean unreadOnly, Pageable pageable) {
        return PageResponse.of(repository.findInbox(CurrentUser.get().userId(), unreadOnly, pageable),
                NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return repository.countUnread(CurrentUser.get().userId());
    }

    @Transactional
    public NotificationResponse markRead(Long id) {
        Notification n = repository.findById(id)
                .filter(x -> CurrentUser.get().userId().equals(x.getRecipientUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Notification", id));
        n.markRead(Instant.now(clock));
        return NotificationResponse.from(n);
    }

    @Transactional
    public int markAllRead() {
        return repository.markAllRead(CurrentUser.get().userId(), Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> byStatus(NotificationStatus status, Pageable pageable) {
        return PageResponse.of(repository.findByStatusOrderByCreatedAtDesc(status, pageable), NotificationResponse::from);
    }

    @Transactional
    public NotificationResponse retry(Long id) {
        Notification n = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Notification", id));
        n.retry(Instant.now(clock));
        return NotificationResponse.from(n);
    }
}
