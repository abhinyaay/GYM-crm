package com.gymops.growth.campaign;

/**
 * DRAFT (one-off or not yet scheduled; can be previewed and run manually) -> ACTIVE (recurring
 * schedule live) <-> PAUSED; any of them -> ARCHIVED (read-only, final).
 */
public enum CampaignStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    ARCHIVED
}
