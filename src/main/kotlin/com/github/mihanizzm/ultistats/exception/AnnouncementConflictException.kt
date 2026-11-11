package com.github.mihanizzm.ultistats.exception

enum class AnnouncementConflictCode {
    ANNOUNCEMENT_CONFLICT,
    ANNOUNCEMENT_ALREADY_OPEN,
    ANNOUNCEMENT_WRITE_DISABLED,
}

class AnnouncementConflictException(val code: AnnouncementConflictCode, val detail: String) : RuntimeException(detail)
