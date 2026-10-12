package com.gymops.notification.preference;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.config.AppProperties;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationCategory;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationProperties;
import com.gymops.notification.channel.LoggingEmailSender;
import com.gymops.notification.preference.NotificationPreference.Source;
import com.gymops.notification.preference.PreferenceDtos.CategoryPreferences;
import com.gymops.notification.preference.PreferenceDtos.ChannelPreference;
import com.gymops.notification.preference.PreferenceDtos.PreferenceChange;
import com.gymops.notification.preference.PreferenceDtos.PreferencesResponse;
import com.gymops.notification.preference.PreferenceDtos.UnsubscribeResponse;
import com.gymops.notification.sms.PhoneNumbers;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member notification preferences (category x EMAIL/SMS). Locked channels (receipts, pre-debit notices,
 * security notices) cannot be switched off. Unsubscribe links are validated by signature only.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;
    private final MemberRepository memberRepository;
    private final AccessPolicy access;
    private final AuditService auditService;
    private final UnsubscribeTokens tokens;
    private final NotificationProperties props;
    private final AppProperties appProperties;
    private final Clock clock;

    /** Effective preferences, for delivery decisions (joins the caller's transaction). */
    @Transactional(readOnly = true)
    public MemberPreferences forMember(Long memberId) {
        return memberId == null ? MemberPreferences.defaults() : MemberPreferences.of(repository.findByMemberId(memberId));
    }

    @Transactional(readOnly = true)
    public PreferencesResponse get(Long memberId) {
        Member member = loadWithAccess(memberId);
        return view(member);
    }

    @Transactional
    public PreferencesResponse update(Long memberId, List<PreferenceChange> changes) {
        Member member = loadWithAccess(memberId);
        UserPrincipal caller = CurrentUser.get();
        Source source = caller.isStaff() ? Source.STAFF : Source.MEMBER;
        AuditEvent audit = AuditEvent.of("MEMBER", member.getId(), "NOTIFICATION_PREFERENCES_UPDATED")
                .member(member.getId()).branch(member.getHomeBranch().getId());
        for (PreferenceChange change : changes) {
            NotificationCategory category = change.category();
            NotificationChannel channel = change.channel();
            if (!category.offers(channel)) {
                throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                        "%s notifications are not available by %s".formatted(category.label(), channel));
            }
            if (category.isLocked(channel) && !change.enabled()) {
                throw new BusinessRuleException("%s by %s cannot be switched off: %s".formatted(category.label(), channel,
                        category.lockedReason()));
            }
            set(member.getId(), category, channel, change.enabled(), source, caller.userId());
            audit.with(category + "_" + channel, change.enabled());
        }
        auditService.record(audit);
        return view(member);
    }

    /** One-click unsubscribe (or undo) from a signed link; no login. */
    @Transactional
    public UnsubscribeResponse unsubscribe(String token, boolean resubscribe) {
        UnsubscribeTokens.Subject subject = tokens.verify(token)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "This unsubscribe link is invalid"));
        Member member = memberRepository.findById(subject.memberId())
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "This unsubscribe link is invalid"));
        NotificationCategory category = subject.category();
        if (category.isLocked(subject.channel()) || !category.offers(subject.channel())) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "This unsubscribe link is invalid");
        }
        set(member.getId(), category, subject.channel(), resubscribe, Source.UNSUBSCRIBE_LINK, null);
        auditService.record(AuditEvent.of("MEMBER", member.getId(), resubscribe ? "RESUBSCRIBED" : "UNSUBSCRIBED")
                .member(member.getId()).branch(member.getHomeBranch().getId())
                .with("category", category).with("channel", subject.channel()));
        return new UnsubscribeResponse(category, category.label(), subject.channel(), resubscribe,
                LoggingEmailSender.mask(member.getEmail()));
    }

    private void set(Long memberId, NotificationCategory category, NotificationChannel channel, boolean enabled, Source source,
                     Long userId) {
        NotificationPreference pref = repository.findByMemberIdAndCategoryAndChannel(memberId, category, channel)
                .orElseGet(() -> new NotificationPreference(memberId, category, channel));
        pref.set(enabled, source, userId, Instant.now(clock));
        repository.saveAndFlush(pref);   // flush: a concurrent insert surfaces here as a mapped 409
    }

    private Member loadWithAccess(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new com.gymops.common.exception.ResourceNotFoundException("Member", memberId));
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        return member;
    }

    private PreferencesResponse view(Member member) {
        MemberPreferences prefs = forMember(member.getId());
        List<CategoryPreferences> categories = new ArrayList<>();
        for (NotificationCategory c : NotificationCategory.values()) {
            List<ChannelPreference> channels = new ArrayList<>();
            for (NotificationChannel ch : List.of(NotificationChannel.EMAIL, NotificationChannel.SMS)) {
                if (c.offers(ch)) {
                    channels.add(new ChannelPreference(ch, prefs.enabled(c, ch), c.isLocked(ch), c.defaultEnabled(ch)));
                }
            }
            categories.add(new CategoryPreferences(c, c.label(), c.description(), c.isPromotional(), c.lockedReason(), channels));
        }
        String sms = PhoneNumbers.toE164(member.getPhone(), props.sms().defaultCountryCode()).orElse(null);
        String quiet = "%02d:00-%02d:00 %s".formatted(props.quietHours().startHour(), props.quietHours().endHour(),
                appProperties.businessTimezone());
        return new PreferencesResponse(member.getId(), member.getEmail(), sms, sms != null, quiet, categories);
    }
}
