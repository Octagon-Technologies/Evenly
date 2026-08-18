package app.splitevenly.domain.feedback

/**
 * The in-app feedback form's vocabulary (ADMIN_FEEDBACK_SPEC.md §4.2).
 *
 * The `wire` values are checked twice on the way out: by `supabase/functions/feedback/index.ts` and
 * again by the `feedback_tickets_*_chk` constraints in `supabase/schema.sql`. Renaming a [label] is a
 * copy change and safe; renaming a `wire` is a three-file migration and breaks every stored ticket's
 * comparability with the ones before it.
 */
enum class FeedbackType(
    val wire: String,
    val label: String,
) {
    Problem("problem", "Problem"),
    Suggestion("suggestion", "Suggestion"),
    Question("question", "Question"),
}

/**
 * Labels are deliberately shorter than the spec's prose ("A missing expense", not "A missing or lost
 * expense"; "How it looks", not "The app's design"). At chip width the spec's wording wraps to two
 * lines and turns the six options into four ragged rows. Owner-approved 2026-08-16; the wire values
 * are the spec's, untouched.
 */
enum class FeedbackCategory(
    val wire: String,
    val label: String,
) {
    Payments("payments", "Payments"),
    Account("account", "Sign-in & account"),
    MissingExpense("missing_expense", "A missing expense"),
    Splitting("splitting", "Splitting & balances"),
    Design("design", "How it looks"),
    Other("other", "Something else"),
}

/** Hard cap from spec §4.3. The edge function refuses anything above this rather than truncating. */
const val FEEDBACK_WORD_CAP = 100

/**
 * Belt to the word cap's braces, matching `MESSAGE_CHAR_CAP` in the edge function. 100 words of prose
 * is ~700 characters; this only catches someone pasting a very long URL.
 */
const val FEEDBACK_CHAR_CAP = 4000

/** The counter stays hidden below this (spec §4.3): a budget on an empty box reads as a limit to hit. */
const val FEEDBACK_COUNTER_FROM = 70

/**
 * Counts words the way the server does, because a client that counts differently either blocks text the
 * server would have taken or waves through text it refuses. The server's rule is
 * `trim().split(/\s+/)`, so this must stay `trim()` + split-on-whitespace-runs and nothing cleverer.
 */
fun feedbackWordCount(text: String): Int {
    val trimmed = text.trim()
    return if (trimmed.isEmpty()) 0 else trimmed.split(WHITESPACE).size
}

private val WHITESPACE = Regex("\\s+")

/**
 * Whether an edit may be applied, given what was there before.
 *
 * Typing is blocked at the cap rather than truncated on submit (spec §4.3): losing someone's last
 * sentence after they hit Send is a genuinely bad moment. A shrinking edit is always allowed, so
 * someone who arrives over the cap by pasting can still delete their way back down rather than being
 * stuck with a field that refuses every keystroke including backspace.
 */
fun feedbackEditAllowed(
    previous: String,
    next: String,
): Boolean {
    if (next.length < previous.length) return true
    return next.length <= FEEDBACK_CHAR_CAP && feedbackWordCount(next) <= FEEDBACK_WORD_CAP
}

/** What the person filled in. Name and email are absent by design: in-app they are already signed in. */
data class FeedbackDraft(
    val type: FeedbackType?,
    val category: FeedbackCategory?,
    val message: String,
)

/** A field the person has not answered yet. Surfaced on tap, never as a disabled Send button. */
enum class FeedbackGap { Type, Category, Message }

/** Empty means ready to send. Order matters: the screen scrolls to the first gap. */
fun FeedbackDraft.gaps(): List<FeedbackGap> =
    buildList {
        if (type == null) add(FeedbackGap.Type)
        if (category == null) add(FeedbackGap.Category)
        if (message.isBlank()) add(FeedbackGap.Message)
    }

/**
 * What happened to a submission.
 *
 * [Queued] is a success, not a failure: the ticket is durably on disk and will go out on its own. The
 * screen says the same thank-you for both, because from the writer's side nothing different happened.
 */
sealed interface FeedbackOutcome {
    data object Sent : FeedbackOutcome

    data object Queued : FeedbackOutcome

    /** The session expired between opening the form and sending. Their words are kept, not discarded. */
    data object NeedsSignIn : FeedbackOutcome

    /** Too many submissions in an hour. Retrying now changes nothing, so the screen must not offer it. */
    data object RateLimited : FeedbackOutcome

    /**
     * The server refused the ticket outright and would refuse it again. This means the client sent
     * something its own validation should have caught, so it is a bug here rather than a blip there.
     * Reported honestly instead of being dressed up as [Queued]: a queue that will never drain is worse
     * than an error, because nobody goes looking for it.
     */
    data object Failed : FeedbackOutcome
}

/** Implemented by `data/repository/FeedbackOutbox`. Bound only when Supabase is configured. */
interface FeedbackSubmitter {
    suspend fun submit(draft: FeedbackDraft): FeedbackOutcome
}
