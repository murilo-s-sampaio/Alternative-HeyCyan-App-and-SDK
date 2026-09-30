package com.fersaiyan.cyanbridge.shared.ble

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBPeripheral
import platform.Foundation.NSData
import kotlin.coroutines.resume

/**
 * Vendor SDK operations for HeyCyan glasses, implemented in Swift by the iOS host
 * with QCSDK.framework (the iOS counterpart of Android's glasses_sdk AAR).
 *
 * QCSDK owns the peripheral after [attach], so the generic CoreBluetooth path in
 * [IosBleManager] stays out of the way while a vendor session is active.
 */
interface VendorGlassesBridge {
    /** 128-bit or 16-bit service UUIDs the vendor glasses advertise. */
    val serviceUuids: List<String>

    fun attach(peripheral: CBPeripheral, completion: VendorResultCallback)
    fun detach(peripheral: CBPeripheral)
    fun setEventListener(listener: VendorGlassesEventListener?)

    fun setDeviceMode(mode: Int, completion: VendorModeCallback)
    fun requestBattery(completion: VendorBatteryCallback)
    fun requestVersion(completion: VendorVersionCallback)
    fun requestMediaCounts(completion: VendorMediaCountsCallback)
    fun syncTime(completion: VendorResultCallback)
}

fun interface VendorResultCallback {
    fun onResult(success: Boolean)
}

/** [currentMode] is the device's mode when the request is rejected. */
fun interface VendorModeCallback {
    fun onResult(success: Boolean, currentMode: Int)
}

fun interface VendorBatteryCallback {
    fun onResult(success: Boolean, level: Int, charging: Boolean)
}

fun interface VendorVersionCallback {
    fun onResult(
        success: Boolean,
        hardware: String,
        firmware: String,
        wifiHardware: String,
        wifiFirmware: String,
    )
}

fun interface VendorMediaCountsCallback {
    fun onResult(success: Boolean, photos: Int, videos: Int, audio: Int)
}

/** Unsolicited updates pushed by the glasses through QCSDKManagerDelegate. */
interface VendorGlassesEventListener {
    fun onBatteryChanged(level: Int, charging: Boolean)
    fun onMediaCountsChanged(photos: Int, videos: Int, audio: Int)
    fun onAiImage(data: NSData)
}

/** Raw values of QCSDK's QCOperatorDeviceMode. */
object VendorGlassesMode {
    const val PHOTO = 0x01
    const val VIDEO = 0x02
    const val VIDEO_STOP = 0x03
    const val TRANSFER = 0x04
    const val OTA = 0x05
    const val AI_PHOTO = 0x06
    const val SPEECH_RECOGNITION = 0x07
    const val AUDIO = 0x08
    const val TRANSFER_STOP = 0x09
    const val AUDIO_STOP = 0x0C
}

/** The Swift host registers its bridge here before creating the Compose controller. */
object VendorGlassesRegistry {
    var bridge: VendorGlassesBridge? = null
}

data class VendorVersionInfo(
    val hardware: String,
    val firmware: String,
    val wifiHardware: String,
    val wifiFirmware: String,
)

data class VendorMediaCounts(val photos: Int, val videos: Int, val audio: Int)

private const val VENDOR_COMMAND_TIMEOUT_MS = 8_000L

suspend fun VendorGlassesBridge.awaitModeAccepted(mode: Int): Boolean = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        setDeviceMode(mode) { success, _ ->
            if (continuation.isActive) continuation.resume(success)
        }
    }
} ?: false

suspend fun VendorGlassesBridge.awaitBattery(): Pair<Int, Boolean>? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        requestBattery { success, level, charging ->
            if (continuation.isActive) continuation.resume(if (success) level to charging else null)
        }
    }
}

suspend fun VendorGlassesBridge.awaitVersion(): VendorVersionInfo? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        requestVersion { success, hardware, firmware, wifiHardware, wifiFirmware ->
            if (continuation.isActive) {
                continuation.resume(
                    if (success) VendorVersionInfo(hardware, firmware, wifiHardware, wifiFirmware) else null,
                )
            }
        }
    }
}

suspend fun VendorGlassesBridge.awaitMediaCounts(): VendorMediaCounts? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        requestMediaCounts { success, photos, videos, audio ->
            if (continuation.isActive) continuation.resume(if (success) VendorMediaCounts(photos, videos, audio) else null)
        }
    }
}

suspend fun VendorGlassesBridge.awaitSyncTime(): Boolean = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        syncTime { success -> if (continuation.isActive) continuation.resume(success) }
    }
} ?: false
