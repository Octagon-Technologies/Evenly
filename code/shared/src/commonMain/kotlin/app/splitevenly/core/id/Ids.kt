package app.splitevenly.core.id

import kotlin.jvm.JvmInline

/**
 * Typed ID wrappers (06 §10). Each wraps a UUIDv7 string (see [app.splitevenly.core.newId]).
 * `value class` = zero runtime overhead with compile-time safety against mixing ID types.
 */
@JvmInline value class UserId(val value: String)

@JvmInline value class GroupId(val value: String)

@JvmInline value class ExpenseId(val value: String)

@JvmInline value class DraftId(val value: String)

@JvmInline value class SettlementId(val value: String)

@JvmInline value class CategoryId(val value: String)

@JvmInline value class ReceiptId(val value: String)

@JvmInline value class CommentId(val value: String)
