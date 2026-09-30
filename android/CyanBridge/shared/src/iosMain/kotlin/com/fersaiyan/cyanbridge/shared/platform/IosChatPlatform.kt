package com.fersaiyan.cyanbridge.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.availableInputs
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.currentLocale
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.languageCode
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIPasteboard
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/** Photos picker, microphone capture, speech output and text sharing for the iOS host. */
@OptIn(ExperimentalForeignApi::class)
object IosChatPlatform {
    private const val MAX_IMAGE_SIDE = 1600.0
    private var pickerDelegate: ImagePickerDelegate? = null
    private var recorder: AVAudioRecorder? = null
    private var recordingUrl: NSURL? = null
    private val synthesizer = AVSpeechSynthesizer()
    private val speechDelegate = SpeechDelegate()

    fun installSharedChatHooks() {
        SharedChatHooks.pickImage = ::pickImage
        SharedChatHooks.startAudioRecording = ::startRecording
        SharedChatHooks.stopAudioRecording = ::stopRecording
        SharedChatHooks.audioMimeType = "audio/mp4"
        SharedChatHooks.copyText = { text -> UIPasteboard.generalPasteboard.string = text }
        SharedChatHooks.shareText = ::shareText
    }

    suspend fun pickImage(): ByteArray? = suspendCancellableCoroutine { continuation ->
        val presenter = IosMediaPlatform.topViewController()
        if (presenter == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val configuration = PHPickerConfiguration()
        configuration.filter = PHPickerFilter.imagesFilter
        configuration.selectionLimit = 1
        val picker = PHPickerViewController(configuration = configuration)
        val delegate = ImagePickerDelegate { bytes ->
            pickerDelegate = null
            if (continuation.isActive) continuation.resume(bytes)
        }
        pickerDelegate = delegate // PHPickerViewController holds its delegate weakly.
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    /** Records AAC audio, preferring the glasses' Bluetooth (HFP) microphone when connected. */
    suspend fun startRecording(): Boolean {
        val session = AVAudioSession.sharedInstance()
        val granted = suspendCancellableCoroutine { continuation ->
            session.requestRecordPermission { allowed -> if (continuation.isActive) continuation.resume(allowed) }
        }
        if (!granted) return false
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            withOptions = AVAudioSessionCategoryOptionAllowBluetooth or AVAudioSessionCategoryOptionDefaultToSpeaker,
            error = null,
        )
        session.setActive(true, error = null)
        session.availableInputs
            ?.filterIsInstance<AVAudioSessionPortDescription>()
            ?.firstOrNull { it.portType == AVAudioSessionPortBluetoothHFP }
            ?.let { session.setPreferredInput(it, error = null) }

        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "cyanbridge-voice.m4a")
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatMPEG4AAC.toInt(),
            AVSampleRateKey to 16_000.0,
            AVNumberOfChannelsKey to 1,
        )
        val audioRecorder = AVAudioRecorder(uRL = url, settings = settings, error = null)
        if (!audioRecorder.record()) return false
        recorder = audioRecorder
        recordingUrl = url
        return true
    }

    suspend fun stopRecording(): ByteArray? {
        val audioRecorder = recorder ?: return null
        audioRecorder.stop()
        recorder = null
        delay(150L) // let AVAudioRecorder finalize the file
        val url = recordingUrl ?: return null
        return NSData.dataWithContentsOfURL(url)?.toKotlinBytes()
    }

    /** Records for a fixed window; used by the glasses voice-question flow. */
    suspend fun recordFor(millis: Long): ByteArray? {
        if (!startRecording()) return null
        delay(millis)
        return stopRecording()
    }

    /** Speaks through the current route (the glasses when connected) and waits until done. */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            withOptions = AVAudioSessionCategoryOptionAllowBluetooth or AVAudioSessionCategoryOptionDefaultToSpeaker,
            error = null,
        )
        session.setActive(true, error = null)
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        NSLocale.currentLocale.languageCode?.let { language ->
            utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(language)
        }
        suspendCancellableCoroutine { continuation ->
            speechDelegate.onFinished = { if (continuation.isActive) continuation.resume(Unit) }
            synthesizer.delegate = speechDelegate
            synthesizer.speakUtterance(utterance)
            continuation.invokeOnCancellation { synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate) }
        }
    }

    fun shareText(text: String) {
        val presenter = IosMediaPlatform.topViewController() ?: return
        val share = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        share.popoverPresentationController?.sourceView = presenter.view
        presenter.presentViewController(share, animated = true, completion = null)
    }

    /** Downscales to [MAX_IMAGE_SIDE] and re-encodes as JPEG for the relay. */
    fun jpegForUpload(data: NSData): ByteArray? {
        val image = UIImage.imageWithData(data) ?: return null
        val (width, height) = image.size.useContents { width to height }
        val scale = minOf(1.0, MAX_IMAGE_SIDE / maxOf(width, height, 1.0))
        val resized = if (scale < 1.0) {
            image.imageByPreparingThumbnailOfSize(CGSizeMake(width * scale, height * scale)) ?: image
        } else {
            image
        }
        return UIImageJPEGRepresentation(resized, 0.85)?.toKotlinBytes()
    }
}

private class SpeechDelegate : NSObject(), AVSpeechSynthesizerDelegateProtocol {
    var onFinished: (() -> Unit)? = null

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
        onFinished?.invoke()
        onFinished = null
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
        onFinished?.invoke()
        onFinished = null
    }
}

private class ImagePickerDelegate(
    private val onPicked: (ByteArray?) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    @OptIn(ExperimentalForeignApi::class)
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) {
            onPicked(null)
            return
        }
        result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            val bytes = data?.let(IosChatPlatform::jpegForUpload)
            dispatch_async(dispatch_get_main_queue()) { onPicked(bytes) }
        }
    }
}
