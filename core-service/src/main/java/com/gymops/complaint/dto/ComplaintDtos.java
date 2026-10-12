package com.gymops.complaint.dto;

import com.gymops.complaint.Complaint;
import com.gymops.complaint.ComplaintCategory;
import com.gymops.complaint.ComplaintComment;
import com.gymops.complaint.ComplaintPriority;
import com.gymops.complaint.ComplaintStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ComplaintDtos {

    private ComplaintDtos() {
    }

    /** Members omit memberId/branchId (taken from their profile); staff filing on behalf provide them. */
    public record CreateRequest(Long memberId, Long branchId, @NotNull ComplaintCategory category,
                                ComplaintPriority priority, @NotBlank @Size(max = 200) String subject,
                                @NotBlank @Size(max = 4000) String description) {
    }

    public record AssignRequest(@NotNull Long assigneeUserId) {
    }

    public record StatusRequest(@NotNull ComplaintStatus status, @Size(max = 4000) String resolution) {
    }

    public record EscalateRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record CommentRequest(@NotBlank @Size(max = 4000) String body, boolean internal) {
    }

    public record ComplaintResponse(Long id, String ticketNumber, Long memberId, Long branchId, Long raisedBy,
                                    ComplaintCategory category, ComplaintPriority priority, ComplaintStatus status,
                                    String subject, String description, Long assignedTo, String resolution,
                                    int escalationLevel, Instant slaDueAt, boolean slaBreached,
                                    Instant firstResponseAt, Instant resolvedAt, Instant closedAt, Instant createdAt,
                                    long version, String raisedByName, String assignedToName) {
        public static ComplaintResponse from(Complaint c, Instant now, java.util.Map<Long, String> names) {
            return new ComplaintResponse(c.getId(), c.getTicketNumber(), c.getMember() == null ? null : c.getMember().getId(),
                    c.getBranch().getId(), c.getRaisedBy(), c.getCategory(), c.getPriority(), c.getStatus(), c.getSubject(),
                    c.getDescription(), c.getAssignedTo(), c.getResolution(), c.getEscalationLevel(), c.getSlaDueAt(),
                    c.isSlaBreached(now), c.getFirstResponseAt(), c.getResolvedAt(), c.getClosedAt(), c.getCreatedAt(),
                    c.getVersion(), names.get(c.getRaisedBy()), c.getAssignedTo() == null ? null : names.get(c.getAssignedTo()));
        }
    }

    public record CommentResponse(Long id, Long authorId, String authorName, String body, boolean internal, Instant createdAt) {
        public static CommentResponse from(ComplaintComment c, java.util.Map<Long, String> names) {
            return new CommentResponse(c.getId(), c.getAuthorId(), names.get(c.getAuthorId()), c.getBody(), c.isInternal(),
                    c.getCreatedAt());
        }
    }
}
