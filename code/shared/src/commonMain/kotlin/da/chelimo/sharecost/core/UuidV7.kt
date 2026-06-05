package da.chelimo.sharecost.core

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * RFC 9562 UUIDv7 generator — pure commonMain, zero third-party deps (06 §5.8).
 *
 * Layout (128 bits):
 * ```
 *  bits 127..80 : 48-bit Unix-millisecond timestamp (sorts by creation time)
 *  bits  79..76 : version = 0b0111 (7)
 *  bits  75..64 : rand_a (12 bits)
 *  bits  63..62 : variant = 0b10
 *  bits  61..0  : rand_b (62 bits)
 * ```
 * `Uuid.toString()` yields the canonical 36-char form a Postgres `uuid` column requires.
 *
 * This is the Kotlin-2.3 baseline (RFC 9562 Method 1: fixed-length random, no intra-millisecond
 * counter). Once the whole dependency set supports Kotlin >= 2.4, replace the body with the stdlib
 * `Uuid.generateV7()`, which additionally guarantees intra-ms monotonicity.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
fun uuidV7(): Uuid {
    val unixMs = Clock.System.now().toEpochMilliseconds() and 0xFFFF_FFFF_FFFFL // low 48 bits
    val randA = Random.nextLong() and 0x0FFFL                                   // 12 bits
    val msb = (unixMs shl 16) or (0x7L shl 12) or randA                         // ts | version | rand_a
    val randB = Random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL                    // low 62 bits
    val lsb = randB or (0x2L shl 62)                                            // variant 0b10 | rand_b
    return Uuid.fromLongs(msb, lsb)
}

/** Canonical 36-char UUIDv7 string for new row IDs. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = uuidV7().toString()
