package com.fluxo.space;

import java.util.EnumSet;
import java.util.Set;

/** Space permission flags (spec §2), granted through space roles. */
public enum Perm {
    BROWSE, CREATE, EDIT, TRANSITION, ASSIGN, BE_ASSIGNABLE, DELETE, LINK,
    COMMENT_ADD, COMMENT_EDIT_OWN, COMMENT_EDIT_ALL, COMMENT_DELETE_OWN, COMMENT_DELETE_ALL,
    ATTACH_ADD, ATTACH_DELETE_OWN, ATTACH_DELETE_ALL,
    MANAGE_WATCHERS, BULK_CHANGE, MANAGE_BOARD, MANAGE_VERSIONS, ADMINISTER_SPACE;

    public static final Set<Perm> ALL = EnumSet.allOf(Perm.class);

    public static final Set<Perm> MEMBER = EnumSet.of(BROWSE, CREATE, EDIT, TRANSITION, ASSIGN, BE_ASSIGNABLE, LINK,
            COMMENT_ADD, COMMENT_EDIT_OWN, COMMENT_DELETE_OWN, ATTACH_ADD, ATTACH_DELETE_OWN, MANAGE_WATCHERS, BULK_CHANGE);

    public static final Set<Perm> VIEWER = EnumSet.of(BROWSE);

    /** What remains of a role's permissions while its space is archived (read-only). */
    public static final Set<Perm> ARCHIVED = EnumSet.of(BROWSE, ADMINISTER_SPACE);
}
