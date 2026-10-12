package com.gymops.access;

import com.gymops.access.GateAuthenticator.DeviceContext;
import com.gymops.access.dto.AccessDtos.AlarmAck;
import com.gymops.access.dto.AccessDtos.AlarmReport;
import com.gymops.access.dto.AccessDtos.Allowlist;
import com.gymops.access.dto.AccessDtos.AllowlistEntry;
import com.gymops.access.dto.AccessDtos.AllowlistHours;
import com.gymops.access.dto.AccessDtos.CommandAck;
import com.gymops.access.dto.AccessDtos.GateCommand;
import com.gymops.access.dto.AccessDtos.GateCommandList;
import com.gymops.access.dto.AccessDtos.HeartbeatRequest;
import com.gymops.access.dto.AccessDtos.HeartbeatResponse;
import com.gymops.branch.GymBranch;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.notification.NotificationType;
import com.gymops.plan.MembershipPlan;
import com.gymops.subscription.Subscription;
import com.gymops.subscription.SubscriptionRepository;
import com.gymops.subscription.SubscriptionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Device-side operations other than scans: heartbeat, command queue, alarms, signed offline allowlist. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceGateService {

    private static final int MAX_COMMANDS_PER_POLL = 50;

    private final AccessDeviceRepository devices;
    private final DeviceCommandRepository commands;
    private final AccessAlarmRepository alarms;
    private final AccessCredentialRepository credentials;
    private final SubscriptionRepository subscriptions;
    private final CredentialService credentialService;
    private final AccessNotifier notifier;
    private final AccessProperties props;
    private final Clock clock;

    @Transactional
    public HeartbeatResponse heartbeat(DeviceContext ctx, HeartbeatRequest request) {
        AccessDevice device = device(ctx);
        Instant now = Instant.now(clock);
        if (device.heartbeat(now, ctx.ip(), request == null ? null : request.firmwareVersion())) {
            log.info("access_device_online device={} branch={}", device.getDeviceUid(), device.getBranch().getId());
        }
        return new HeartbeatResponse(now, device.getStatus(), commands.countOpen(device.getId()),
                (int) Math.min(Duration.ofHours(props.offlineAllowlistHours()).toSeconds() / 4, 3600));
    }

    /** Open commands, oldest first; each poll re-delivers until the device acknowledges (at-least-once). */
    @Transactional
    public GateCommandList pollCommands(DeviceContext ctx) {
        Instant now = Instant.now(clock);
        List<GateCommand> result = new ArrayList<>();
        for (DeviceCommand c : commands.findOpen(ctx.deviceId(), PageRequest.of(0, MAX_COMMANDS_PER_POLL))) {
            c.markDelivered(now);
            result.add(new GateCommand(c.getId(), c.getCommandType(), c.getCredentialType(), c.getTemplateRef(),
                    c.getMemberRef(), c.getCreatedAt(), c.getDeliveryCount()));
        }
        return new GateCommandList(result);
    }

    @Transactional
    public GateCommand acknowledge(DeviceContext ctx, Long commandId, CommandAck ack) {
        DeviceCommand command = commands.findById(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Command", commandId));
        if (!command.getDevice().getId().equals(ctx.deviceId())) {
            throw new ForbiddenException("Command " + commandId + " belongs to another device");
        }
        if (command.getCommandType() == CommandType.ENROLL_TEMPLATE) {
            credentialService.completeEnrollment(command, ack.success(), ack.templateRef(), ack.message());
        } else {
            command.complete(ack.success(), ack.message(), Instant.now(clock));
        }
        return new GateCommand(command.getId(), command.getCommandType(), command.getCredentialType(),
                command.getTemplateRef(), command.getMemberRef(), command.getCreatedAt(), command.getDeliveryCount());
    }

    /** Door forced / held open / tailgating / tamper: logged once per device event id, branch managers notified. */
    @Transactional
    public AlarmAck reportAlarm(DeviceContext ctx, AlarmReport report) {
        Optional<AccessAlarm> existing = alarms.findByDeviceIdAndDeviceEventId(ctx.deviceId(), report.deviceEventId());
        if (existing.isPresent()) {
            return new AlarmAck(existing.get().getId(), existing.get().getAlarmType(), true);
        }
        AccessDevice device = device(ctx);
        Instant now = Instant.now(clock);
        Instant occurredAt = report.occurredAt() == null || report.occurredAt().isAfter(now) ? now : report.occurredAt();
        AccessAlarm alarm = alarms.saveAndFlush(new AccessAlarm(device.getId(), device.getBranch().getId(),
                report.deviceEventId(), report.type(), report.details(), occurredAt, now));
        notifier.notifyBranchManagers(device.getBranch().getId(), NotificationType.ACCESS_ALARM,
                "Gate alarm: " + label(report.type()) + " at " + device.getName(),
                label(report.type()) + " reported by " + device.getName() + " (" + device.getBranch().getName() + ") at "
                        + occurredAt + (report.details() == null ? "" : ": " + report.details()));
        log.warn("access_alarm device={} type={} alarm={}", device.getDeviceUid(), report.type(), alarm.getId());
        return new AlarmAck(alarm.getId(), alarm.getAlarmType(), false);
    }

    /**
     * Offline allowlist for this device: every active card / biometric credential whose member may enter this
     * branch during the next N hours (active member, access-granting subscription, plan valid at this branch),
     * each with its validity window and the plan's daily access window. Visit caps and live capacity cannot be
     * enforced offline. The controller signs the exact response bytes with the device secret.
     */
    @Transactional(readOnly = true)
    public Allowlist allowlist(DeviceContext ctx) {
        AccessDevice device = device(ctx);
        GymBranch branch = device.getBranch();
        ZoneId zone = branch.zone();
        Instant now = Instant.now(clock);
        Instant horizon = now.plus(Duration.ofHours(props.offlineAllowlistHours()));
        List<AccessCredential> active = credentials.findActiveForAllowlist();
        Map<Long, List<Subscription>> subsByMember = active.isEmpty() ? Map.of()
                : subscriptions.findByMembersAndStatuses(active.stream().map(c -> c.getMember().getId()).distinct().toList(),
                        SubscriptionStatus.ACCESS_GRANTING).stream()
                        .collect(Collectors.groupingBy(s -> s.getMember().getId()));
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<AllowlistEntry> entries = new ArrayList<>();
        for (AccessCredential c : active) {
            Member member = c.getMember();
            Optional<Subscription> sub = subsByMember.getOrDefault(member.getId(), List.of()).stream()
                    .filter(s -> s.grantsAccessOn(today))
                    .filter(s -> s.getPlan().isAllBranchAccess() || s.getBranch().getId().equals(branch.getId()))
                    .findFirst();
            if (sub.isEmpty()) {
                continue;
            }
            Instant until = lastAccessDay(sub.get(), today, horizon, zone);
            MembershipPlan plan = sub.get().getPlan();
            entries.add(new AllowlistEntry(c.getType(), c.getCredentialHash(), member.getMemberCode(),
                    GateDecisionService.displayName(member), now, until.isAfter(horizon) ? horizon : until,
                    plan.getAccessStartTime(), plan.getAccessEndTime()));
        }
        entries.sort(Comparator.comparing(AllowlistEntry::memberRef).thenComparing(AllowlistEntry::credentialHash));
        List<AllowlistHours> hours = branch.getOperatingHours().stream()
                .map(h -> new AllowlistHours(h.getDayOfWeek(), h.getOpenTime(), h.getCloseTime(), h.isClosed()))
                .toList();
        return new Allowlist(device.getDeviceUid(), branch.getId(), branch.getCode(), zone.getId(), now, horizon,
                "RFID_CARD: sha256(\"RFID_CARD:\" + UPPERCASE_HEX_UID); FINGERPRINT/FACE: sha256(\"TEMPLATE:\" + type + \":\" + "
                        + "templateRef)", hours, entries,
                "Offline mode cannot enforce monthly visit caps, live capacity or QR passes; upload buffered events with "
                        + "offlineDecision set when the connection returns.");
    }

    /** End (exclusive) of the last day, within the horizon, on which the subscription still grants access. */
    private static Instant lastAccessDay(Subscription sub, LocalDate today, Instant horizon, ZoneId zone) {
        LocalDate day = today;
        LocalDate horizonDay = LocalDate.ofInstant(horizon, zone);
        while (!day.isAfter(horizonDay) && sub.grantsAccessOn(day.plusDays(1))) {
            day = day.plusDays(1);
        }
        return day.plusDays(1).atStartOfDay(zone).toInstant();
    }

    private AccessDevice device(DeviceContext ctx) {
        return devices.findById(ctx.deviceId()).orElseThrow(() -> new ResourceNotFoundException("Device", ctx.deviceId()));
    }

    static String label(AlarmType type) {
        return switch (type) {
            case DOOR_FORCED -> "Door forced open";
            case DOOR_HELD_OPEN -> "Door held open";
            case TAILGATING -> "Tailgating";
            case TAMPER -> "Device tamper";
            case DEVICE_OFFLINE -> "Device offline";
        };
    }
}
