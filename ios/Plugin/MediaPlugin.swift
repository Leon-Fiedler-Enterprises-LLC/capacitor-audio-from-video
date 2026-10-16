import Capacitor
import CoreGraphics
import Foundation

/// The app's media processing on iOS: extractAudio (AudioExtractor), and share videos and shared
/// audio at another playback speed (StillVideoEncoder).
@objc(MediaPlugin)
public class MediaPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MediaPlugin"
    public let jsName = "Media"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "extractAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "prepare", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createStillVideo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "changeAudioSpeed", returnType: CAPPluginReturnPromise),
    ]

    private let audioExtractor = AudioExtractor()
    private let prefetch = Prefetch()

    /// The audio of a video or audio file, cut and shrunk for upload (see AudioExtractor).
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
        
        audioExtractor.extractAudio(videoURL: url!, outputURL: outputUrl) { resultUrl, error in
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
                    "dataUrl": self.audioExtractor.getDataURL(from: resultUrl)!,
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

    /// Starts the downloads and the encoder while the share sheet is open. The audio download
    /// alone (a signed CDN URL) takes about 0.4 s, several times the encode of a short message.
    @objc func prepare(_ call: CAPPluginCall) {
        let audio = call.getString("audioUrl").flatMap { Self.isRemote($0) ? $0 : nil }
        let images = [call.getString("imageUrl"), call.getString("overlayUrl")].compactMap { $0 }.filter(Self.isRemote)
        prefetch.replace(audio: audio, images: images)
        Task { await StillVideoEncoder.warmUp() }
        call.resolve()
    }

    @objc func createStillVideo(_ call: CAPPluginCall) {
        guard let audio = call.getString("audioUrl"),
              let output = call.getString("outputPath").flatMap(Self.fileURL),
              let image = call.getString("imageUrl") else {
            call.reject("audioUrl, outputPath and imageUrl are required")
            return
        }
        let overlay = call.getString("overlayUrl")
        let width = call.getInt("width") ?? 0
        let height = call.getInt("height") ?? 0
        let hd = call.getBool("hd") ?? false
        let speedFactor = call.getDouble("speedFactor") ?? 1

        Task {
            do {
                // The downloads and the encoder's start-up overlap.
                async let warmUp: Void = StillVideoEncoder.warmUp()
                async let audioFile = self.audioFile(audio)
                async let voiceImage = self.loadImage(image)
                async let overlayImage = self.loadImage(overlay)
                let (audioURL, temporary) = try await audioFile
                defer { if temporary { try? FileManager.default.removeItem(at: audioURL) } }
                let options = StillVideoOptions(
                    audioURL: audioURL, image: try await voiceImage, overlay: try await overlayImage,
                    width: width, height: height, hd: hd, speedFactor: speedFactor, outputURL: output)
                await warmUp
                let duration = try await StillVideoEncoder.encode(options) { progress in
                    self.notifyListeners("progress", data: ["progress": progress])
                }
                call.resolve(["path": output.absoluteString, "duration": duration])
            } catch {
                call.reject(error.localizedDescription, nil, error)
            }
        }
    }

    /// The audio at another tempo with the pitch kept, as AAC in an .m4a (no MP3 encoder on iOS).
    @objc func changeAudioSpeed(_ call: CAPPluginCall) {
        guard let audio = call.getString("audioUrl"),
              let output = call.getString("outputPath").flatMap(Self.fileURL) else {
            call.reject("audioUrl and outputPath are required")
            return
        }
        let speedFactor = call.getDouble("speedFactor") ?? 1

        Task {
            do {
                let (audioURL, temporary) = try await self.audioFile(audio)
                defer { if temporary { try? FileManager.default.removeItem(at: audioURL) } }
                let duration = try await StillVideoEncoder.encodeAudio(audioURL, speedFactor: speedFactor, outputURL: output)
                call.resolve(["path": output.absoluteString, "duration": duration])
            } catch {
                call.reject(error.localizedDescription, nil, error)
            }
        }
    }

    /// The audio as a local file: the file itself, prepare()'s download (kept for another video
    /// of the same message until the next prepare()), or a new download (`temporary`).
    private func audioFile(_ source: String) async throws -> (url: URL, temporary: Bool) {
        if let url = Self.fileURL(source) { return (url, false) }
        if let prefetched = prefetch.audio(source), let file = try? await prefetched.value { return (file, false) }
        return (try await Self.downloadAudio(source), true)
    }

    private func loadImage(_ source: String?) async throws -> CGImage? {
        guard let source else { return nil }
        if let prefetched = prefetch.image(source), let data = try? await prefetched.value {
            return try StillVideoEncoder.decodeImage(data)
        }
        return try StillVideoEncoder.decodeImage(try await Self.data(from: source))
    }

    /// Downloads into the temporary directory. AVFoundation picks the demuxer by file extension,
    /// so the file keeps a known one.
    fileprivate static func downloadAudio(_ source: String) async throws -> URL {
        guard let remote = URL(string: source), isRemote(source) else { throw StillVideoError.writer("unsupported audio URL") }
        let (file, response) = try await URLSession.shared.download(from: remote)
        defer { try? FileManager.default.removeItem(at: file) }
        if let status = (response as? HTTPURLResponse)?.statusCode, !(200..<300).contains(status) {
            throw StillVideoError.writer("audio download failed (\(status))")
        }
        let known: Set<String> = ["mp3", "m4a", "aac", "wav", "aif", "aiff", "caf", "mp4", "m4v", "mov"]
        let ext = [remote.pathExtension, (response.suggestedFilename as NSString?)?.pathExtension ?? ""]
            .map { $0.lowercased() }.first(where: known.contains) ?? "mp3"
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent("still-video-\(UUID().uuidString).\(ext)")
        try FileManager.default.moveItem(at: file, to: destination)
        return destination
    }

    fileprivate static func isRemote(_ source: String) -> Bool {
        source.hasPrefix("https://") || source.hasPrefix("http://")
    }

    /// https URLs, data URLs, file URLs and paths, and the WebView's local file URLs.
    fileprivate static func data(from source: String) async throws -> Data {
        if source.hasPrefix("data:") {
            guard let comma = source.firstIndex(of: ","), source[..<comma].hasSuffix(";base64"),
                  let data = Data(base64Encoded: String(source[source.index(after: comma)...]), options: .ignoreUnknownCharacters) else {
                throw StillVideoError.frame("unsupported data URL")
            }
            return data
        }
        if let url = fileURL(source) {
            return try Data(contentsOf: url)
        }
        guard let url = URL(string: source), url.scheme == "https" || url.scheme == "http" else {
            throw StillVideoError.frame("unsupported image URL")
        }
        let (data, response) = try await URLSession.shared.data(from: url)
        if let status = (response as? HTTPURLResponse)?.statusCode, !(200..<300).contains(status) {
            throw StillVideoError.frame("image download failed (\(status))")
        }
        return data
    }

    /// file:// URLs, absolute paths, and Capacitor.convertFileSrc URLs
    /// (capacitor://localhost/_capacitor_file_/...).
    private static func fileURL(_ source: String) -> URL? {
        let localPrefix = "/_capacitor_file_"
        if let url = URL(string: source), url.host == "localhost", url.path.hasPrefix(localPrefix) {
            return URL(fileURLWithPath: String(url.path.dropFirst(localPrefix.count)))
        }
        if source.hasPrefix("file://") { return URL(string: source) }
        if source.hasPrefix("/") { return URL(fileURLWithPath: source) }
        return nil
    }
}

/// Downloads started by prepare(), for the message whose share sheet is open.
private final class Prefetch: @unchecked Sendable {
    private let lock = NSLock()
    private var audioDownload: (source: String, file: Task<URL, Error>)?
    private var imageDownloads: [String: Task<Data, Error>] = [:]

    /// Keeps downloads that are still wanted (the same sheet opened again) and drops the rest.
    func replace(audio source: String?, images sources: [String]) {
        lock.lock()
        let previous = audioDownload
        if previous?.source != source {
            audioDownload = source.map { source in (source, Task { try await MediaPlugin.downloadAudio(source) }) }
        }
        imageDownloads = Dictionary(uniqueKeysWithValues: Set(sources).map { source in
            (source, imageDownloads[source] ?? Task { try await MediaPlugin.data(from: source) })
        })
        lock.unlock()
        if let previous, previous.source != source {
            Task { if let file = try? await previous.file.value { try? FileManager.default.removeItem(at: file) } }
        }
    }

    func audio(_ source: String) -> Task<URL, Error>? {
        lock.lock()
        defer { lock.unlock() }
        return audioDownload?.source == source ? audioDownload?.file : nil
    }

    func image(_ source: String) -> Task<Data, Error>? {
        lock.lock()
        defer { lock.unlock() }
        return imageDownloads[source]
    }
}
