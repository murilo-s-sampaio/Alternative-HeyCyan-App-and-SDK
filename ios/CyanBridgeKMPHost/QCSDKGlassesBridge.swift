import CoreBluetooth
import CyanBridgeShared
import Foundation
#if canImport(QCSDK)
import QCSDK
#endif

/// Registers the vendor SDK bridge before the shared Compose controller starts.
enum VendorGlassesSetup {
    static func register() {
        #if canImport(QCSDK)
        VendorGlassesRegistry.shared.bridge = QCSDKGlassesBridge.shared
        #endif
    }
}

#if canImport(QCSDK)
/// QCSDK.framework implementation of the shared `VendorGlassesBridge`.
/// QCSDK ships only an arm64 device slice, so simulator builds skip this file's body.
final class QCSDKGlassesBridge: NSObject, VendorGlassesBridge, QCSDKManagerDelegate {
    static let shared = QCSDKGlassesBridge()

    private var eventListener: VendorGlassesEventListener?

    private override init() {
        super.init()
        QCSDKManager.shareInstance().delegate = self
    }

    var serviceUuids: [String] { [QCSDKSERVERUUID1, QCSDKSERVERUUID2] }

    func attach(peripheral: CBPeripheral, completion: VendorResultCallback) {
        let manager = QCSDKManager.shareInstance()
        manager.delegate = self
        manager.remove(peripheral)
        manager.add(peripheral) { success in
            completion.onResult(success: success)
        }
    }

    func detach(peripheral: CBPeripheral) {
        QCSDKManager.shareInstance().remove(peripheral)
    }

    func setEventListener(listener: VendorGlassesEventListener?) {
        eventListener = listener
    }

    func setDeviceMode(mode: Int32, completion: VendorModeCallback) {
        guard let deviceMode = QCOperatorDeviceMode(rawValue: Int(mode)) else {
            completion.onResult(success: false, currentMode: 0)
            return
        }
        QCSDKCmdCreator.setDeviceMode(deviceMode, success: {
            completion.onResult(success: true, currentMode: mode)
        }, fail: { currentMode in
            completion.onResult(success: false, currentMode: Int32(currentMode))
        })
    }

    func requestBattery(completion: VendorBatteryCallback) {
        QCSDKCmdCreator.getDeviceBattery({ level, charging in
            completion.onResult(success: true, level: Int32(level), charging: charging)
        }, fail: {
            completion.onResult(success: false, level: 0, charging: false)
        })
    }

    func requestVersion(completion: VendorVersionCallback) {
        QCSDKCmdCreator.getDeviceVersionInfoSuccess({ hardware, firmware, wifiHardware, wifiFirmware in
            completion.onResult(
                success: true,
                hardware: hardware,
                firmware: firmware,
                wifiHardware: wifiHardware,
                wifiFirmware: wifiFirmware
            )
        }, fail: {
            completion.onResult(success: false, hardware: "", firmware: "", wifiHardware: "", wifiFirmware: "")
        })
    }

    func requestMediaCounts(completion: VendorMediaCountsCallback) {
        QCSDKCmdCreator.getDeviceMedia({ photos, videos, audio, _ in
            completion.onResult(success: true, photos: Int32(photos), videos: Int32(videos), audio: Int32(audio))
        }, fail: {
            completion.onResult(success: false, photos: 0, videos: 0, audio: 0)
        })
    }

    func syncTime(completion: VendorResultCallback) {
        QCSDKCmdCreator.setupDeviceDateTime { success, _ in
            completion.onResult(success: success)
        }
    }

    // MARK: QCSDKManagerDelegate

    func didUpdateBatteryLevel(_ battery: Int, charging: Bool) {
        eventListener?.onBatteryChanged(level: Int32(battery), charging: charging)
    }

    func didUpdateMedia(withPhotoCount photo: Int, videoCount video: Int, audioCount audio: Int, type: Int) {
        eventListener?.onMediaCountsChanged(photos: Int32(photo), videos: Int32(video), audio: Int32(audio))
    }

    func didReceiveAIChatImageData(_ imageData: Data) {
        eventListener?.onAiImage(data: imageData)
    }
}
#endif
