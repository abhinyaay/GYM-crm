package com.gymops.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Per-branch gate behaviour set by the gym owner (absent row = {@code app.access.default-exit-mode}). */
@Getter
@Entity
@Table(name = "access_branch_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessBranchSettings {

    @Id
    @Column(name = "branch_id")
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "exit_mode", nullable = false)
    private ExitMode exitMode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    AccessBranchSettings(Long branchId) {
        this.branchId = branchId;
    }

    void update(ExitMode mode, Long userId, Instant now) {
        this.exitMode = mode;
        this.updatedBy = userId;
        this.updatedAt = now;
    }
}
