import Foundation
import Capacitor


@objc(AudioFromVideoRetrieverPlugin)
public class AudioFromVideoRetrieverPlugin: CAPPlugin {
    private let implementation = AudioFromVideoRetriever()

    @objc func extractAudio(_ call: CAPPluginCall) {
        let path = call.getString("path") ?? ""
        let includeData = call.getBool("includeData") ?? false
        
        let url = URL(string: path)
        // Determine output URL: use provided outputPath if valid, else create a cache file with .m4a extension
        let outputUrl: URL = {
            if let outputPath = call.getString("outputPath"),
               outputPath.isEmpty == false,
               let provided = URL(string: outputPath) {
                return provided
            }
            let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            let fname = "voicesai_afv_\(Int(Date().timeIntervalSince1970)).m4a"
            let dest = caches.appendingPathComponent(fname)
            // Remove if exists to avoid export errors
            try? FileManager.default.removeItem(at: dest)
            return dest
        }()
        
        implementation.extractAudio(videoURL: url!, outputURL: outputUrl) { resultUrl, error in
            guard error == nil else {
                call.reject(error!.localizedDescription)
                return
            }
            
            guard let resultUrl = resultUrl else {
                call.reject("Unexpected error occurred during audio extraction.")
                return
            }
            
            let fileAttributes = try? FileManager.default.attributesOfItem(atPath: resultUrl.path)
            let fileSize = (fileAttributes?[.size] as? NSNumber)?.int64Value ?? 0
            let mimeType = "audio/mp4"

            if includeData {
                call.resolve([
                    "path": resultUrl.absoluteString,
                    "dataUrl": self.implementation.getDataURL(from: resultUrl)!,
                    "fileSize": fileSize,
                    "mimeType": mimeType,
                ])
                return
            }
            call.resolve([
                "path": resultUrl.absoluteString,
                "fileSize": fileSize,
                "mimeType": mimeType,
            ])
        }
    }
}
