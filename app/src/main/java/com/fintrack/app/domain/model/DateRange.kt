package com.fintrack.app.domain.model

import java.time.Instant

/** Half-open UTC interval [start, endExclusive). */
data class DateRange(val start: Instant, val endExclusive: Instant) {
    init {
        require(start < endExclusive) { "start must be before endExclusive" }
    }
}
