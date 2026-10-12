package com.gymops.notification.admin;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.config.AppProperties;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.Notification;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationDispatcher;
import com.gymops.notification.NotificationProperties;
import com.gymops.notification.NotificationRepository;
import com.gymops.notification.NotificationStatus;
import com.gymops.notification.NotificationType;
import com.gymops.notification.admin.MessagingDtos.NotificationLogEntry;
import com.gymops.notification.admin.MessagingDtos.ProviderStatus;
import com.gymops.notification.admin.MessagingDtos.ProvidersResponse;
import com.gymops.notification.admin.MessagingDtos.TestMessageRequest;
import com.gymops.notification.channel.LoggingEmailSender;
import com.gymops.notification.sms.PhoneNumbers;
import com.gymops.notification.sms.SmsGateway;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Messaging centre: search the notification log, resend failed messages, send test messages and report which
 * providers are active. Branch-scoped managers see notifications of members of their branches plus the ones they
 * triggered themselves.
 */
@Service
public class MessagingService {

    /** Search filters (all optional). {@code from}/{@code to} are business-time dates, inclusive. */
    public record Filter(NotificationChannel channel, NotificationStatus status, NotificationType type, Long memberId,
                         LocalDate from, LocalDate to, String q) {
    }

    private final NotificationRepository repository;
    private final MemberRepository memberRepository;
    private final NotificationDispatcher dispatcher;
    private final AccessPolicy access;
    private final AuditService auditService;
    private final NotificationProperties props;
    private final SmsGateway smsGateway;
    private final ObjectProvider<MailProperties> mailProperties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zone;

    public MessagingService(NotificationRepository repository, MemberRepository memberRepository, NotificationDispatcher dispatcher,
                            AccessPolicy access, AuditService auditService, NotificationProperties props, SmsGateway smsGateway,
                            ObjectProvider<MailProperties> mailProperties, TransactionTemplate tx, Clock clock,
                            AppProperties appProperties) {
        this.repository = repository;
        this.memberRepository = memberRepository;
        this.dispatcher = dispatcher;
        this.access = access;
        this.auditService = auditService;
        this.props = props;
        this.smsGateway = smsGateway;
        this.mailProperties = mailProperties;
        this.tx = tx;
        this.clock = clock;
        this.zone = ZoneId.of(appProperties.businessTimezone());
    }

    // ------------------------------------------------------------------ log

    @Transactional(readOnly = true)
    public PageResponse<NotificationLogEntry> search(Filter filter, Pageable pageable) {
        Page<Notification> page = repository.findAll(specification(filter, CurrentUser.get()), pageable);
        Map<Long, String> names = memberNames(page.getContent());
        return PageResponse.of(page, n -> NotificationLogEntry.from(n, n.getMemberId() == null ? null : names.get(n.getMemberId())));
    }

    @Transactional(readOnly = true)
    public NotificationLogEntry get(Long id) {
        Notification n = loadVisible(id);
        String name = n.getMemberId() == null ? null : memberNames(List.of(n)).get(n.getMemberId());
        return NotificationLogEntry.from(n, name);
    }

    /** Re-queues a FAILED notification (to the member's current address) and attempts delivery immediately. */
    public NotificationLogEntry resend(Long id) {
        tx.executeWithoutResult(status -> {
            Notification n = loadVisible(id);
            if (n.getStatus() != NotificationStatus.FAILED) {
                throw new InvalidStateTransitionException("notification", n.getStatus(), "resend");
            }
            String destination = null;
            if (n.getMemberId() != null && n.getChannel() != NotificationChannel.IN_APP) {
                Member member = memberRepository.findById(n.getMemberId())
                        .orElseThrow(() -> new ResourceNotFoundException("Member", n.getMemberId()));
                destination = currentDestination(member, n.getChannel());
            }
            n.resend(destination, CurrentUser.get().userId(), Instant.now(clock));
            auditService.record(AuditEvent.of("NOTIFICATION", n.getId(), "RESENT").member(n.getMemberId())
                    .with("channel", n.getChannel()).with("type", n.getType()));
        });
        dispatcher.dispatchNow(id);
        return tx.execute(status -> get(id));
    }

    // ------------------------------------------------------------------ test messages

    public NotificationLogEntry sendTest(TestMessageRequest request) {
        NotificationChannel channel = request.channel();
        if (channel != NotificationChannel.EMAIL && channel != NotificationChannel.SMS) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Test messages can be sent by EMAIL or SMS");
        }
        UserPrincipal caller = CurrentUser.get();
        Long id = tx.execute(status -> {
            Long memberId = null;
            Long branchId = null;
            String destination;
            if (request.memberId() != null) {
                Member member = memberRepository.findById(request.memberId())
                        .orElseThrow(() -> new ResourceNotFoundException("Member", request.memberId()));
                access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
                memberId = member.getId();
                branchId = member.getHomeBranch().getId();
                destination = currentDestination(member, channel);
            } else {
                if (!caller.isOrgWide()) {
                    throw new ForbiddenException("Only admins may send test messages to arbitrary addresses; pick a member");
                }
                destination = explicitDestination(request.to(), channel);
            }
            Notification n = new Notification(NotificationType.TEST_MESSAGE, channel, null, memberId, destination,
                    "Test message from " + props.email().fromName(), testBody(channel, request.message(), caller),
                    null, Instant.now(clock)).requestedBy(caller.userId());
            repository.save(n);
            auditService.record(AuditEvent.of("NOTIFICATION", n.getId(), "TEST_SENT").member(memberId).branch(branchId)
                    .with("channel", channel).with("provider", providerName(channel))
                    .with("to", channel == NotificationChannel.SMS ? PhoneNumbers.mask(destination)
                            : LoggingEmailSender.mask(destination)));
            return n.getId();
        });
        dispatcher.dispatchNow(id);
        return tx.execute(status -> get(id));
    }

    // ------------------------------------------------------------------ providers

    public ProvidersResponse providers() {
        Map<String, Object> email = new LinkedHashMap<>();
        email.put("fromAddress", props.email().fromAddress());
        email.put("fromName", props.email().fromName());
        boolean emailConfigured = true;
        if ("smtp".equals(props.emailProvider())) {
            MailProperties mail = mailProperties.getIfAvailable();
            boolean hasHost = mail != null && mail.getHost() != null && !mail.getHost().isBlank();
            boolean hasUser = mail != null && mail.getUsername() != null && !mail.getUsername().isBlank();
            boolean hasPassword = mail != null && mail.getPassword() != null && !mail.getPassword().isBlank();
            email.put("host", hasHost ? mail.getHost() : null);
            email.put("port", mail == null ? null : mail.getPort());
            email.put("authentication", hasUser);
            email.put("credentials", hasUser ? (hasPassword ? "present" : "password missing") : "none");
            emailConfigured = hasHost && (!hasUser || hasPassword);
        }
        Map<String, Object> sms = new LinkedHashMap<>();
        NotificationProperties.Sms s = props.sms();
        sms.put("enabled", s.enabled());
        sms.put("defaultCountryCode", "+" + s.defaultCountryCode());
        sms.put("maxLength", s.maxLength());
        switch (smsGateway.name()) {
            case "twilio" -> {
                sms.put("sender", notBlank(s.twilio().messagingServiceSid()) ? "messaging service"
                        : notBlank(s.twilio().fromNumber()) ? s.twilio().fromNumber() : null);
                sms.put("credentials", notBlank(s.twilio().accountSid()) && notBlank(s.twilio().authToken()) ? "present" : "missing");
            }
            case "msg91" -> {
                sms.put("senderId", s.msg91().senderId());
                sms.put("templateId", s.msg91().templateId());
                sms.put("dltTemplateId", notBlank(s.msg91().dltTemplateId()) ? "set" : "not set");
                sms.put("typeTemplates", s.msg91().templates() == null ? 0 : s.msg91().templates().size());
                sms.put("credentials", notBlank(s.msg91().authKey()) ? "present" : "missing");
            }
            default -> { }
        }
        String quiet = "%02d:00-%02d:00 %s".formatted(props.quietHours().startHour(), props.quietHours().endHour(), zone);
        return new ProvidersResponse(
                new ProviderStatus(NotificationChannel.EMAIL, props.emailProvider(), !"log".equals(props.emailProvider()),
                        emailConfigured, email),
                new ProviderStatus(NotificationChannel.SMS, smsGateway.name(), !"log".equals(smsGateway.name()),
                        smsGateway.configured(), sms),
                quiet, props.publicBaseUrl());
    }

    // ------------------------------------------------------------------ helpers

    private String providerName(NotificationChannel channel) {
        return channel == NotificationChannel.SMS ? smsGateway.name() : props.emailProvider();
    }

    private String currentDestination(Member member, NotificationChannel channel) {
        if (channel == NotificationChannel.SMS) {
            return PhoneNumbers.toE164(member.getPhone(), props.sms().defaultCountryCode())
                    .orElseThrow(() -> new BusinessRuleException("Member " + member.getId() + " has no usable mobile number"));
        }
        return member.getEmail();
    }

    private String explicitDestination(String to, NotificationChannel channel) {
        if (to == null || to.isBlank()) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Give a member or a destination ('to')");
        }
        if (channel == NotificationChannel.SMS) {
            return PhoneNumbers.toE164(to, props.sms().defaultCountryCode())
                    .orElseThrow(() -> new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Not a valid mobile number"));
        }
        try {
            InternetAddress address = new InternetAddress(to.strip(), true);
            address.validate();
            return address.getAddress();
        } catch (AddressException e) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Not a valid e-mail address");
        }
    }

    private String testBody(NotificationChannel channel, String message, UserPrincipal caller) {
        String custom = message == null || message.isBlank() ? "" : message.strip();
        if (channel == NotificationChannel.SMS) {
            String text = props.email().fromName() + " test SMS via " + smsGateway.name() + ". " + custom;
            return text.strip();
        }
        String body = ("This is a test e-mail sent from the %s messaging centre by %s via the '%s' provider.\n\n"
                + "If you can read it, e-mail delivery is set up correctly.").formatted(props.email().fromName(),
                caller.email(), props.emailProvider());
        return custom.isEmpty() ? body : custom + "\n\n" + body;
    }

    /** Branch-scoped staff see notifications of members of their branches and the ones they triggered. */
    private Notification loadVisible(Long id) {
        Notification n = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Notification", id));
        UserPrincipal caller = CurrentUser.get();
        if (caller.isOrgWide() || Objects.equals(n.getRequestedByUserId(), caller.userId())) {
            return n;
        }
        boolean inScope = n.getMemberId() != null && memberRepository.findById(n.getMemberId())
                .map(m -> caller.branchIds().contains(m.getHomeBranch().getId())).orElse(false);
        if (!inScope) {
            throw new ResourceNotFoundException("Notification", id);
        }
        return n;
    }

    private Specification<Notification> specification(Filter f, UserPrincipal caller) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.channel() != null) {
                p.add(cb.equal(root.get("channel"), f.channel()));
            }
            if (f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.type() != null) {
                p.add(cb.equal(root.get("type"), f.type()));
            }
            if (f.memberId() != null) {
                p.add(cb.equal(root.get("memberId"), f.memberId()));
            }
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from().atStartOfDay(zone).toInstant()));
            }
            if (f.to() != null) {
                p.add(cb.lessThan(root.get("createdAt"), f.to().plusDays(1).atStartOfDay(zone).toInstant()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("destination")), like, '\\'),
                        cb.like(cb.lower(root.get("title")), like, '\\')));
            }
            if (!caller.isOrgWide()) {
                Subquery<Long> members = query.subquery(Long.class);
                var m = members.from(Member.class);
                Set<Long> branches = caller.branchIds().isEmpty() ? Set.of(-1L) : caller.branchIds();
                members.select(m.get("id")).where(m.get("homeBranch").get("id").in(branches));
                p.add(cb.or(root.get("memberId").in(members), cb.equal(root.get("requestedByUserId"), caller.userId())));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    private Map<Long, String> memberNames(List<Notification> rows) {
        Set<Long> ids = rows.stream().map(Notification::getMemberId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return memberRepository.findAllById(ids).stream().collect(Collectors.toMap(Member::getId, Member::fullName, (a, b) -> a));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
