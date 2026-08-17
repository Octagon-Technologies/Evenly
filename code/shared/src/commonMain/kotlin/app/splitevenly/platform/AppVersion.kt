package app.splitevenly.platform

/**
 * The running build, as `"1.4.0 (218)"`, or `""` when it cannot be determined.
 *
 * Attached to every feedback ticket (ADMIN_FEEDBACK_SPEC.md §4.1): half of triaging a bug report is
 * knowing whether it is already fixed. The server caps this at 40 characters, so keep the format short.
 * Empty rather than a placeholder like "unknown" on failure, because the column is nullable and a
 * literal "unknown" would sort and group as if it were a real version.
 */
expect fun appVersionLabel(): String
