package com.gymops.user;

import java.util.EnumSet;
import java.util.Set;

public enum RoleName {
    SUPER_ADMIN,
    GYM_ADMIN,
    MANAGER,
    TRAINER,
    RECEPTIONIST,
    MEMBER;

    /** Roles whose authority spans every branch of the organisation. */
    public static final Set<RoleName> ORG_WIDE = EnumSet.of(SUPER_ADMIN, GYM_ADMIN);

    /** Staff roles that only operate within the branches they are assigned to. */
    public static final Set<RoleName> BRANCH_SCOPED_STAFF = EnumSet.of(MANAGER, TRAINER, RECEPTIONIST);

    public String authority() {
        return "ROLE_" + name();
    }
}
