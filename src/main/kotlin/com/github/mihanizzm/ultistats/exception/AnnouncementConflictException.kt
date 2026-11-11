package com.github.mihanizzm.ultistats.exception

enum class AnnouncementConflictCode {
    ANNOUNCEMENT_CONFLICT,
    ANNOUNCEMENT_ALREADY_OPEN,
    ANNOUNCEMENT_WRITE_DISABLED,
    ANNOUNCEMENT_NOT_CLOSED,
    ANNOUNCEMENT_ATTRIBUTION_STALE,
}

class AnnouncementConflictException(val code: AnnouncementConflictCode, val detail: String) : RuntimeException(detail)
