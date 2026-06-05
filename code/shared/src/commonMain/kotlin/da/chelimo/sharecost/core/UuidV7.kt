package da.chelimo.sharecost

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
 *  bits  75..64 : rand_a (12 bits, monotonic counter within same ms)
 *  bits  63..62 : variant = 0b10
 *  bits  61..0  : rand_b (62 bits)
 * ```
 * `Uuid.toString()` yields the canonical 36-char form a Postgres `uuid` column requires.
 * Intra-millisecond monotonicity is guaranteed via a 12-bit counter in rand_a (RFC 9562 Method 2).
 */
private var lastMs = -1L
private var seqCounter = 0L

@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
fun uuidV7(): Uuid {
    val unixMs = Clock.System.now().toEpochMilliseconds() and 0xFFFF_FFFF_FFFFL
    val randA = if (unixMs > lastMs) {
        lastMs = unixMs
        seqCounter = Random.nextLong() and 0x0FFFL
        seqCounter
    } else {
        seqCounter = (seqCounter + 1) and 0x0FFFL
        seqCounter
    }
    val msb = (unixMs shl 16) or (0x7L shl 12) or randA
    val randB = Random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL
    val lsb = randB or (0x2L shl 62)
    return Uuid.fromLongs(msb, lsb)
}

/** Canonical 36-char UUIDv7 string for new row IDs. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = uuidV7().toString()

/** Test-facing alias: generates a canonical 36-char UUIDv7 string. */
@OptIn(ExperimentalUuidApi::class)
fun generateUuidV7(): String = uuidV7().toString()
