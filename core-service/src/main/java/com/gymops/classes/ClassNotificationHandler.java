package com.gymops.classes;

import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Translates group-class events into member notifications (IN_APP + EMAIL via the outbox). */
@Component
@RequiredArgsConstructor
public class ClassNotificationHandler {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");

    private final NotificationService notifications;
    private final ClassBookingRepository bookingRepository;
    private final ClassSessionRepository sessionRepository;
    private final MemberRepository memberRepository;

    @EventListener
    public void on(ClassEvents.ClassBooked e) {
        bookingRepository.findDetailed(e.bookingId()).ifPresent(b -> notifications.notifyMember(b.getMember(),
                NotificationType.CLASS_BOOKED, "Class booked: " + b.getSession().getClassType().getName(),
                "You're booked for %s. Free cancellation until %s.".formatted(describe(b.getSession()),
                        when(b.getSession(), b.getSession().cancelCutoffAt())),
                "CLASS_BOOKED:" + b.getId()));
    }

    @EventListener
    public void on(ClassEvents.ClassWaitlisted e) {
        bookingRepository.findDetailed(e.bookingId()).ifPresent(b -> notifications.notifyMember(b.getMember(),
                NotificationType.CLASS_WAITLISTED, "Waitlisted: " + b.getSession().getClassType().getName(),
                "The class is full; you're #%d on the waitlist for %s. We'll book you automatically if a spot opens."
                        .formatted(e.position(), describe(b.getSession())),
                "CLASS_WAITLISTED:" + b.getId()));
    }

    @EventListener
    public void on(ClassEvents.WaitlistPromoted e) {
        bookingRepository.findDetailed(e.bookingId()).ifPresent(b -> notifications.notifyMember(b.getMember(),
                NotificationType.CLASS_WAITLIST_PROMOTED, "You're in: " + b.getSession().getClassType().getName(),
                "A spot opened up and you're now booked for %s.".formatted(describe(b.getSession())),
                "CLASS_PROMOTED:" + b.getId()));
    }

    @EventListener
    public void on(ClassEvents.SessionCancelled e) {
        sessionRepository.findDetailed(e.sessionId()).ifPresent(s -> e.memberIds().forEach(memberId -> {
            Member member = memberRepository.findById(memberId).orElse(null);
            if (member != null) {
                String reason = s.getCancellationReason() == null ? "" : " Reason: " + s.getCancellationReason() + ".";
                notifications.notifyMember(member, NotificationType.CLASS_CANCELLED, "Class cancelled: " + s.getClassType().getName(),
                        "%s has been cancelled.%s Your booking has been released and no credit was used."
                                .formatted(describe(s), reason),
                        "CLASS_CANCELLED:" + s.getId() + ":" + memberId);
            }
        }));
    }

    @EventListener
    public void on(ClassEvents.SessionRescheduled e) {
        sessionRepository.findDetailed(e.sessionId()).ifPresent(s -> e.memberIds().forEach(memberId ->
                memberRepository.findById(memberId).ifPresent(member -> notifications.notifyMember(member,
                        NotificationType.CLASS_RESCHEDULED, "Class updated: " + s.getClassType().getName(),
                        "Your class has changed: it is now %s.".formatted(describe(s)), null))));
    }

    @EventListener
    public void on(ClassEvents.ReminderDue e) {
        bookingRepository.findDetailed(e.bookingId()).ifPresent(b -> notifications.notifyMember(b.getMember(),
                NotificationType.CLASS_REMINDER, "Starting soon: " + b.getSession().getClassType().getName(),
                "Reminder: %s. Can't make it? Cancelling now counts as a late cancellation.".formatted(describe(b.getSession())),
                "CLASS_REMINDER:" + b.getId()));
    }

    private static String describe(ClassSession s) {
        String room = s.getRoom() == null ? "" : ", " + s.getRoom();
        return "%s with %s on %s at %s%s".formatted(s.getClassType().getName(), s.getInstructor().getUser().getFullName(),
                when(s, s.getStartsAt()), s.getBranch().getName(), room);
    }

    private static String when(ClassSession s, java.time.Instant instant) {
        return TIME.format(instant.atZone(s.getBranch().zone()));
    }
}
