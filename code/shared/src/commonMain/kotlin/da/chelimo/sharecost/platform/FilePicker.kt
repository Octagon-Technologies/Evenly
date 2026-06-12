package da.chelimo.sharecost.platform

import da.chelimo.sharecost.core.error.AppResult

/** What the user is allowed to pick (06 §5.1) — narrows the system picker's content types. */
enum class PickKind { Image, Pdf, ImageOrPdf }

/** A picked file's bytes plus the metadata the receipt pipeline (D-22) needs to compress/upload it. */
class PickedFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * E-5 — photo / PDF / file picker (06 §5.1) returning `(name, mime, bytes)`. **Android**:
 * `ActivityResultContracts.OpenDocument` launched against the current `ComponentActivity`, bytes read
 * through the `ContentResolver`. **iOS**: `UIDocumentPickerViewController` presented on the key window's
 * root view controller, bytes read from the security-scoped URL.
 *
 * [pick] suspends until the user picks or cancels. A cancel resolves to `AppResult.Ok(null)` — it is a
 * normal outcome, not an error; only a genuine failure (no host activity, unreadable bytes) is an `Err`.
 *
 * Constructor is platform-specific (Android needs a way to reach the foreground activity) — instances
 * come from `platformModule()`.
 */
expect class FilePicker {
    /** Present the system picker constrained to [kind]; `Ok(null)` if the user cancels. */
    suspend fun pick(kind: PickKind = PickKind.ImageOrPdf): AppResult<PickedFile?>
}
