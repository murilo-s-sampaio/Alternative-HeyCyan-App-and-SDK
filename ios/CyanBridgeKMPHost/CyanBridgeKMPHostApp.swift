import SwiftUI
import CyanBridgeShared

@main
struct CyanBridgeKMPHostApp: App {
    init() {
        VendorGlassesSetup.register()
        IosSecurityBridgeImpl.register()
        LocalModelBridgeImpl.register()
        if let selfTest = ProcessInfo.processInfo.environment["CYANBRIDGE_LOCAL_MODEL_SELFTEST"], !selfTest.isEmpty {
            IosLocalModels.shared.runSelfTest(modelName: selfTest)
        }
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        if let destination = ProcessInfo.processInfo.environment["CYANBRIDGE_SCREENSHOT_DESTINATION"],
           !destination.isEmpty {
            return MainViewControllerKt.MainViewControllerForDestination(destination: destination)
        }
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
