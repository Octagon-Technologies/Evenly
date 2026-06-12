package da.chelimo.sharecost.core.time

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Small time helpers over the stdlib [kotlin.time.Clock] (06 §5.8 uses the same clock for UUIDv7).
 * Repositories take an injectable [Clock] (default [Clock.System]) so timestamps are deterministic
 * under test. Centralising the `ExperimentalTime` opt-in here keeps it out of every call site.
 *
 * Note: kotlinx-datetime 0.7.x moved `Clock`/`Instant` to the `kotlin.time` stdlib package, so
 * `kotlinx.datetime.Clock` no longer resolves — see the project build-gotchas memory.
 */

/** Epoch milliseconds — the unit every `*_at` column stores (02 §7: `timestamptz` → `Long`). */
@OptIn(ExperimentalTime::class)
fun Clock.nowEpochMillis(): Long = now().toEpochMilliseconds()

/** Today's calendar date in UTC as an ISO-8601 string ("YYYY-MM-DD") — the FX rate-date key (03 §6). */
@OptIn(ExperimentalTime::class)
fun Clock.todayUtc(): String = now().toLocalDateTime(TimeZone.UTC).date.toString()
