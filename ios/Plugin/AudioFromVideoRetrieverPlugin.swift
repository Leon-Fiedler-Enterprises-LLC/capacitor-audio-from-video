import Capacitor
import Foundation

/// The plugin's name before it became Media, with only extractAudio. Older web bundles (Live
/// Updates) call it on newer binaries. Remove once no supported web bundle uses it.
@objc(AudioFromVideoRetrieverPlugin)
public class AudioFromVideoRetrieverPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "AudioFromVideoRetrieverPlugin"
    public let jsName = "AudioFromVideoRetriever"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "extractAudio", returnType: CAPPluginReturnPromise),
    ]

    private let audioExtractor = AudioExtractor()

    @objc func extractAudio(_ call: CAPPluginCall) {
        audioExtractor.extractAudio(call)
    }
}
