package app.splitevenly.domain.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The word cap is enforced in three places (here, the edge function, the check constraints) and they
 * have to agree. These pin the client half against the server's `trim().split(/\s+/)`.
 */
class FeedbackDraftTest {
    @Test
    fun `empty and blank text count as zero words`() {
        assertEquals(0, feedbackWordCount(""))
        assertEquals(0, feedbackWordCount("   \n\t "))
    }

    @Test
    fun `runs of whitespace count as one separator like the server`() {
        assertEquals(3, feedbackWordCount("one  two\n\tthree"))
        assertEquals(3, feedbackWordCount("  one two three  "))
    }

    @Test
    fun `an edit that reaches the cap is allowed and one past it is not`() {
        val atCap = (1..FEEDBACK_WORD_CAP).joinToString(" ") { "w" }
        assertEquals(FEEDBACK_WORD_CAP, feedbackWordCount(atCap))
        assertTrue(feedbackEditAllowed("", atCap))
        assertFalse(feedbackEditAllowed(atCap, "$atCap more"))
    }

    @Test
    fun `deleting is always allowed even from over the cap`() {
        // Someone can arrive over the cap by pasting. If shrinking edits were refused too, the field
        // would reject backspace and strand them.
        val over = (1..FEEDBACK_WORD_CAP + 20).joinToString(" ") { "w" }
        assertTrue(feedbackEditAllowed(over, over.dropLast(2)))
    }

    @Test
    fun `the character cap blocks a paste that is under the word cap`() {
        val oneHugeWord = "x".repeat(FEEDBACK_CHAR_CAP + 1)
        assertEquals(1, feedbackWordCount(oneHugeWord))
        assertFalse(feedbackEditAllowed("", oneHugeWord))
    }

    @Test
    fun `gaps name every unanswered field in screen order`() {
        assertEquals(
            listOf(FeedbackGap.Type, FeedbackGap.Category, FeedbackGap.Message),
            FeedbackDraft(null, null, "").gaps(),
        )
        assertEquals(
            listOf(FeedbackGap.Category),
            FeedbackDraft(FeedbackType.Problem, null, "it broke").gaps(),
        )
        assertTrue(
            FeedbackDraft(FeedbackType.Problem, FeedbackCategory.Payments, "it broke").gaps().isEmpty(),
        )
    }

    @Test
    fun `a whitespace-only message is still a gap`() {
        assertEquals(
            listOf(FeedbackGap.Message),
            FeedbackDraft(FeedbackType.Question, FeedbackCategory.Other, "   ").gaps(),
        )
    }

    @Test
    fun `wire values match the server's accepted lists exactly`() {
        // These strings are duplicated in supabase/functions/feedback/index.ts and schema.sql. A rename
        // on one side only is a 400 on every submission, which is exactly the kind of break that shows
        // up in production rather than in a compile.
        assertEquals(
            listOf("problem", "suggestion", "question"),
            FeedbackType.entries.map { it.wire },
        )
        assertEquals(
            listOf("payments", "account", "missing_expense", "splitting", "design", "other"),
            FeedbackCategory.entries.map { it.wire },
        )
    }
}
