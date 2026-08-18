@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.splitevenly.platform

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationScopeEmail
import platform.AuthenticationServices.ASAuthorizationScopeFullName
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSError
import platform.Foundation.NSPersonNameComponentsFormatter
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS [AppleSignIn] (Apple Sign In native plan §2). `AuthenticationServices` is a system framework, so
 * Kotlin/Native calls it directly, the same way [FilePicker] calls `PhotosUI`/`UIKit` — no Swift host
 * code needed. The active delegate is held strongly for the (single, suspended) request so ARC doesn't
 * drop it before the callback, mirroring [FilePicker]'s `activeDelegate` field.
 */
actual class AppleSignIn {

    private var activeDelegate: NSObject? = null

    actual suspend fun signIn(): AppResult<AppleSignInResult> = withContext(Dispatchers.Main) {
        val rawNonce = randomNonce()
        val hashedNonce = sha256Hex(rawNonce)

        suspendCancellableCoroutine { cont ->
            val request = ASAuthorizationAppleIDProvider().createRequest().apply {
                requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)
                nonce = hashedNonce
            }
            val delegate = AppleSignInDelegate(rawNonce) { result ->
                activeDelegate = null
                cont.resume(result)
            }
            activeDelegate = delegate
            val controller = ASAuthorizationController(authorizationRequests = listOf(request))
            controller.delegate = delegate
            controller.presentationContextProvider = delegate
            cont.invokeOnCancellation { activeDelegate = null }
            controller.performRequests()
        }
    }

    /** Apple's documented nonce recipe: a CSPRNG-sourced string over an unambiguous charset. */
    private fun randomNonce(length: Int = 32): String {
        val charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._"
        val result = StringBuilder(length)
        memScoped {
            while (result.length < length) {
                val byte = allocArray<UByteVar>(1)
                SecRandomCopyBytes(kSecRandomDefault, 1u.convert(), byte)
                val random = byte[0].toInt() and 0xFF
                if (random < charset.length) result.append(charset[random])
            }
        }
        return result.toString()
    }

    /** SHA-256 over UTF-8 bytes, lowercase hex — the hash Apple's `nonce` request field expects. */
    private fun sha256Hex(input: String): String = memScoped {
        val inputBytes = input.encodeToByteArray()
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        inputBytes.usePinned { inPinned ->
            digest.usePinned { outPinned ->
                CC_SHA256(inPinned.addressOf(0), inputBytes.size.convert(), outPinned.addressOf(0))
            }
        }
        digest.joinToString("") { it.toString(16).padStart(2, '0') }
    }
}

private class AppleSignInDelegate(
    private val rawNonce: String,
    private val onResult: (AppResult<AppleSignInResult>) -> Unit,
) : NSObject(),
    ASAuthorizationControllerDelegateProtocol,
    ASAuthorizationControllerPresentationContextProvidingProtocol {

    override fun authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization: ASAuthorization,
    ) {
        val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
        val identityToken = credential?.identityToken?.toByteArray()?.decodeToString()
        if (credential == null || identityToken == null) {
            onResult(AppResult.Err(AppError.Unexpected(IllegalStateException("No Apple identity token"))))
            return
        }
        val fullName = credential.fullName?.let {
            NSPersonNameComponentsFormatter().stringFromPersonNameComponents(it).takeIf(String::isNotBlank)
        }
        // authorizationCode is one-time-use and only meaningful server-side (apple-link-token exchanges
        // it for a refresh token); a fresh authorization can lack it in rare re-auth flows, so it rides
        // through as nullable rather than failing the whole sign-in over a token-revocation nicety.
        val authorizationCode = credential.authorizationCode?.toByteArray()?.decodeToString()
        onResult(AppResult.Ok(AppleSignInResult(identityToken, rawNonce, fullName, authorizationCode)))
    }

    override fun authorizationController(controller: ASAuthorizationController, didCompleteWithError: NSError) {
        onResult(AppResult.Err(AppError.Unexpected(RuntimeException(didCompleteWithError.localizedDescription))))
    }

    override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): UIWindow {
        val application = UIApplication.sharedApplication
        return application.keyWindow ?: (application.windows.firstOrNull() as? UIWindow) ?: UIWindow()
    }
}
