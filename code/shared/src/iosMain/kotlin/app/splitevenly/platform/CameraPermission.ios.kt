package app.splitevenly.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import kotlin.coroutines.resume

/**
 * iOS [CameraPermission] — `AVCaptureDevice` authorization for `AVMediaTypeVideo`.
 *
 * `Restricted` (parental controls / MDM) folds into [CameraPermissionStatus.Denied] on purpose: the user
 * cannot grant it from our prompt either way, and the recovery screen's trip to Settings is the only useful
 * next step in both cases.
 *
 * The completion handler fires on an arbitrary queue, so callers that touch UIKit afterwards must hop back
 * to the main dispatcher themselves. [FilePicker] already does.
 */
actual class CameraPermission {

    actual suspend fun status(): CameraPermissionStatus =
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> CameraPermissionStatus.Granted
            AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted -> CameraPermissionStatus.Denied
            else -> CameraPermissionStatus.NotDetermined
        }

    actual suspend fun request(): Boolean = suspendCancellableCoroutine { cont ->
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
            // A refusal isn't an error: the caller offers Photos instead.
            cont.resume(granted)
        }
    }
}
