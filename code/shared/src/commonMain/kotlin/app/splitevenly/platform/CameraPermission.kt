package app.splitevenly.platform

/** Where the OS currently stands on letting us open the camera. */
enum class CameraPermissionStatus {
    /** Never asked. A [CameraPermission.request] will show the system prompt. */
    NotDetermined,
    Granted,
    /** Asked and refused, or blocked by policy. The OS will not prompt again; only settings undo it. */
    Denied,
    /** No runtime grant exists on this platform. Android hands off to the system camera app. */
    NotApplicable,
}

/**
 * Runtime camera permission (iOS `AVCaptureDevice`; nothing to grant on Android).
 *
 * This exists because presenting `UIImagePickerController` without checking first is a dead end: iOS shows
 * a **grey, frozen viewfinder** to anyone who has refused once, with no prompt and no way back. Every
 * camera entry point must go through [status] first, and route [CameraPermissionStatus.Denied] to a screen
 * that explains the trip to Settings.
 *
 * Android reports [CameraPermissionStatus.NotApplicable] unconditionally. `FilePicker` uses `TakePicture`,
 * which delegates to the system camera app, and `CAMERA` is deliberately absent from the manifest, so
 * there is no grant to hold. Adding the manifest entry would *create* a prompt we currently avoid, and
 * would silently turn this into a real gate.
 */
expect class CameraPermission {
    /** What the OS says right now; never shows UI. */
    suspend fun status(): CameraPermissionStatus

    /** Show the system prompt if one is still available; returns whether the camera may open afterwards. */
    suspend fun request(): Boolean
}
