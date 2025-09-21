import AVFoundation

@objc public class AudioFromVideoRetriever: NSObject {
    @objc public func extractAudio(videoURL: URL, outputURL: URL, completion: @escaping (URL?, Error?) -> Void) {
        let asset = AVURLAsset(url: videoURL)
        let audioTrack = asset.tracks(withMediaType: .audio).first
        
        guard let audioTrack = audioTrack else {
            let error = NSError(domain: "AudioExtractionError", code: 0, userInfo: [NSLocalizedDescriptionKey: "No audio track found in the video."])
            completion(nil, error)
            return
        }
        
        let composition = AVMutableComposition()
        let audioCompositionTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        
        // Limit duration to 5 minutes (300 seconds)
        let maxDuration = CMTime(seconds: 300, preferredTimescale: 600)
        let timeRangeToUse = CMTimeRange(start: .zero, duration: min(audioTrack.timeRange.duration, maxDuration))
        
        do {
            try audioCompositionTrack?.insertTimeRange(timeRangeToUse, of: audioTrack, at: .zero)
        } catch {
            completion(nil, error)
            return
        }
        
        func startExport(preset: String, fallback: Bool, _ done: @escaping (Bool, Error?) -> Void) {
            // Remove any pre-existing file at destination to avoid errors
            try? FileManager.default.removeItem(at: outputURL)
            guard let session = AVAssetExportSession(asset: composition, presetName: preset) else {
                done(false, NSError(domain: "AudioExtractionError", code: -2, userInfo: [NSLocalizedDescriptionKey: "Export session not available for preset \(preset)"]))
                return
            }
            session.outputFileType = .m4a
            session.outputURL = outputURL
            session.timeRange = timeRangeToUse
            session.fileLengthLimit = Int64(9.9 * 1024 * 1024) // ~10 MB
            session.exportAsynchronously {
                switch session.status {
                case .completed:
                    done(true, nil)
                case .failed, .cancelled:
                    done(false, session.error)
                default:
                    done(false, session.error)
                }
            }
        }

        // Prefer passthrough when supported, else fall back to Apple M4A
        let supported = AVAssetExportSession.exportPresets(compatibleWith: composition)
        let canPassthrough = supported.contains(AVAssetExportPresetPassthrough)

        if canPassthrough {
            startExport(preset: AVAssetExportPresetPassthrough, fallback: false) { ok, err in
                if ok {
                    completion(outputURL, nil)
                } else {
                    startExport(preset: AVAssetExportPresetAppleM4A, fallback: true) { ok2, err2 in
                        completion(ok2 ? outputURL : nil, ok2 ? nil : (err2 ?? err))
                    }
                }
            }
        } else {
            startExport(preset: AVAssetExportPresetAppleM4A, fallback: true) { ok, err in
                completion(ok ? outputURL : nil, err)
            }
        }
    }
    
    public func getDataURL(from audioURL: URL) -> String? {
        do {
            let audioData = try Data(contentsOf: audioURL)
            let base64String = audioData.base64EncodedString()
            let mediaType = "audio/mp4" // Set the appropriate media type for your audio file
            let dataURLString = "data:\(mediaType);base64,\(base64String)"
            return dataURLString
        } catch {
            print("Error: \(error)")
            return nil
        }
    }
}
