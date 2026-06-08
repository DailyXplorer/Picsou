package com.picsou.dto;

/**
 * Runtime urgency of a recurring series' next due date, <em>computed</em> at response time and
 * never stored — {@code recurring_status} is a native (append-only) PG enum, so these transient
 * states live only in the DTO. Drives the "late" / "due soon" badges on the subscriptions page.
 *
 * @see RecurringSeriesResponse#from(com.picsou.model.RecurringSeries, java.time.LocalDate)
 */
public enum RecurringRuntimeStatus {
    /** Next due date is already in the past — the expected charge has not been seen. */
    LATE,
    /** Next due date falls within the next week. */
    DUE_SOON,
    /** Next due date is further out (or unknown). */
    SCHEDULED
}
