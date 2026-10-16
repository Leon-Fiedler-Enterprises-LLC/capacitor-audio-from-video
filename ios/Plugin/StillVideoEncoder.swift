import Accelerate
import AudioToolbox
import AVFoundation
import CoreGraphics
import Foundation
import ImageIO
import VideoToolbox

/// Share videos: one still frame plus a generation's audio.
///
/// Mirrors buildStillSegmentArgs / buildStillVideoMuxArgs in the app's src/services/stillVideo.ts,
/// which Android and the web still run through ffmpeg.wasm. The frame is composed and encoded
/// once into a short H.264 segment (a keyframe followed by frames that repeat it), and that
/// segment is copied for the length of the audio, so the video costs the same for any length.
/// Only the audio is encoded in full, by the system AAC encoder.
public struct StillVideoOptions {
    public var audioURL: URL
    public var image: CGImage?
    /// Watermark artwork, authored at 9:16.
    public var overlay: CGImage?
    /// Even, because the video is 4:2:0.
    public var width: Int
    public var height: Int
    public var hd: Bool
    /// Tempo change that keeps the pitch, like ffmpeg's atempo.
    public var speedFactor: Double
    public var outputURL: URL

    public init(audioURL: URL, image: CGImage?, overlay: CGImage?, width: Int, height: Int, hd: Bool, speedFactor: Double, outputURL: URL) {
        self.audioURL = audioURL
        self.image = image
        self.overlay = overlay
        self.width = width
        self.height = height
        self.hd = hd
        self.speedFactor = speedFactor
        self.outputURL = outputURL
    }
}

public enum StillVideoError: LocalizedError {
    case invalidSize(Int, Int)
    case noAudioTrack
    case frame(String)
    case encoder(String, OSStatus)
    case writer(String)

    public var errorDescription: String? {
        switch self {
        case let .invalidSize(width, height): return "Invalid video size \(width)x\(height)"
        case .noAudioTrack: return "The audio file has no audio track"
        case let .frame(message): return "Couldn't compose the video frame: \(message)"
        case let .encoder(message, status): return "\(message) (\(status))"
        case let .writer(message): return message
        }
    }
}

public enum StillVideoEncoder {
    static let framesPerSecond: Int32 = 1
    /// Length of the repeated segment, which is also the keyframe interval.
    static let segmentSeconds = 10
    static let audioBitRate = 128_000

    /// Writes the video and returns its duration in seconds. `progress` gets 0...1.
    public static func encode(_ options: StillVideoOptions, progress: @escaping (Double) -> Void = { _ in }) async throws -> Double {
        let (width, height) = (options.width, options.height)
        guard width > 0, height > 0, width % 2 == 0, height % 2 == 0 else {
            throw StillVideoError.invalidSize(width, height)
        }

        let audio = try await AudioSource(url: options.audioURL, speedFactor: options.speedFactor)
        let duration = audio.duration
        // The last frame takes the remainder, so a 60.003 s message doesn't end on a 3 ms keyframe.
        let frameCount = max(1, Int((duration.seconds * Double(framesPerSecond)).rounded()))

        let frame = try composeFrame(width: width, height: height, image: options.image, overlay: options.overlay)
        let segment = try encodeSegment(frame, frames: min(frameCount, segmentSeconds * Int(framesPerSecond)), hd: options.hd)

        try? FileManager.default.removeItem(at: options.outputURL)
        let writer = try AVAssetWriter(outputURL: options.outputURL, fileType: .mp4)
        // moov before mdat, so players and uploads can start before the whole file is read.
        writer.shouldOptimizeForNetworkUse = true

        // Passthrough: the segment's samples are already H.264.
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: CMSampleBufferGetFormatDescription(segment[0]))
        videoInput.expectsMediaDataInRealTime = false
        let audioInput = audio.makeWriterInput()
        guard writer.canAdd(videoInput), writer.canAdd(audioInput) else {
            throw StillVideoError.writer("Couldn't set up the video writer")
        }
        writer.add(videoInput)
        writer.add(audioInput)

        guard audio.reader.startReading() else {
            throw audio.reader.error ?? StillVideoError.writer("Couldn't read the audio")
        }
        guard writer.startWriting() else {
            audio.reader.cancelReading()
            throw writer.error ?? StillVideoError.writer("Couldn't start writing the video")
        }
        writer.startSession(atSourceTime: .zero)

        let pump = Pump()
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                pump.start(continuation, inputs: 2)
                audio.feed(audioInput, of: writer, pump: pump, progress: progress)

                var index = 0
                videoInput.requestMediaDataWhenReady(on: DispatchQueue(label: "still-video.video")) {
                    while videoInput.isReadyForMoreMediaData, !pump.isFinished {
                        guard index < frameCount else {
                            videoInput.markAsFinished()
                            pump.inputFinished()
                            return
                        }
                        let start = CMTime(value: CMTimeValue(index), timescale: framesPerSecond)
                        let length = index == frameCount - 1 ? CMTimeSubtract(duration, start) : CMTime(value: 1, timescale: framesPerSecond)
                        let sample: CMSampleBuffer
                        do {
                            sample = try retime(segment[index % segment.count], start: start, duration: length)
                        } catch {
                            pump.fail(error)
                            return
                        }
                        guard videoInput.append(sample) else {
                            pump.fail(writer.error ?? StillVideoError.writer("Couldn't write the video frames"))
                            return
                        }
                        index += 1
                    }
                }
            }
        } catch {
            audio.reader.cancelReading()
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: options.outputURL)
            throw error
        }

        writer.endSession(atSourceTime: duration)
        await writer.finishWriting()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: options.outputURL)
            throw writer.error ?? StillVideoError.writer("Couldn't finish the video")
        }
        progress(1)
        return duration.seconds
    }

    /// Writes the audio alone at another tempo, like ffmpeg's atempo, and returns its duration in
    /// seconds. AVFoundation has no MP3 encoder, so it's AAC-LC in an .m4a, as in the videos.
    public static func encodeAudio(_ audioURL: URL, speedFactor: Double, outputURL: URL) async throws -> Double {
        let audio = try await AudioSource(url: audioURL, speedFactor: speedFactor)

        try? FileManager.default.removeItem(at: outputURL)
        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .m4a)
        writer.shouldOptimizeForNetworkUse = true
        let audioInput = audio.makeWriterInput()
        guard writer.canAdd(audioInput) else { throw StillVideoError.writer("Couldn't set up the audio writer") }
        writer.add(audioInput)

        guard audio.reader.startReading() else {
            throw audio.reader.error ?? StillVideoError.writer("Couldn't read the audio")
        }
        guard writer.startWriting() else {
            audio.reader.cancelReading()
            throw writer.error ?? StillVideoError.writer("Couldn't start writing the audio")
        }
        writer.startSession(atSourceTime: .zero)

        let pump = Pump()
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                pump.start(continuation, inputs: 1)
                audio.feed(audioInput, of: writer, pump: pump) { _ in }
            }
        } catch {
            audio.reader.cancelReading()
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: outputURL)
            throw error
        }

        writer.endSession(atSourceTime: audio.duration)
        await writer.finishWriting()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: outputURL)
            throw writer.error ?? StillVideoError.writer("Couldn't finish the audio")
        }
        return audio.duration.seconds
    }

    /// The first H.264 session in a process takes about 0.2 s to start. A throwaway one made
    /// while the inputs download makes the real one start in milliseconds.
    public static func warmUp() async {
        var created: VTCompressionSession?
        guard VTCompressionSessionCreate(
            allocator: nil, width: 64, height: 64, codecType: kCMVideoCodecType_H264, encoderSpecification: nil,
            imageBufferAttributes: nil, compressedDataAllocator: nil, outputCallback: nil, refcon: nil,
            compressionSessionOut: &created) == noErr, let session = created else { return }
        VTCompressionSessionPrepareToEncodeFrames(session)
        VTCompressionSessionInvalidate(session)
    }

    // MARK: Frame

    /// Decodes WebP, JPEG, PNG, HEIC and the like, upright (EXIF orientation applied).
    public static func decodeImage(_ data: Data) throws -> CGImage {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int else {
            throw StillVideoError.frame("unreadable image")
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(width, height),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw StillVideoError.frame("undecodable image")
        }
        return image
    }

    /// #13161d canvas, the voice image cover-fitted (scaled up to fill, center crop) and the
    /// watermark at full height, centered. Converted to 4:2:0 once, with BT.709 coefficients.
    static func composeFrame(width: Int, height: Int, image: CGImage?, overlay: CGImage?) throws -> CVPixelBuffer {
        guard let srgb = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: srgb,
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
              ) else {
            throw StillVideoError.frame("no bitmap context")
        }
        let canvas = CGRect(x: 0, y: 0, width: width, height: height)
        context.interpolationQuality = .high
        context.setFillColor(CGColor(srgbRed: 0x13 / 255, green: 0x16 / 255, blue: 0x1d / 255, alpha: 1))
        context.fill(canvas)

        if let image {
            let scale = max(canvas.width / CGFloat(image.width), canvas.height / CGFloat(image.height))
            let size = CGSize(width: CGFloat(image.width) * scale, height: CGFloat(image.height) * scale)
            context.draw(image, in: CGRect(x: (canvas.width - size.width) / 2, y: (canvas.height - size.height) / 2, width: size.width, height: size.height))
        }
        if let overlay {
            // ffmpeg's scale=-2:H: the aspect-correct width, rounded to an even number.
            let overlayWidth = CGFloat(2 * Int((Double(height) * Double(overlay.width) / Double(overlay.height) / 2).rounded()))
            context.draw(overlay, in: CGRect(x: (canvas.width - overlayWidth) / 2, y: 0, width: overlayWidth, height: canvas.height))
        }

        var pixelBuffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary
        guard CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attributes, &pixelBuffer) == kCVReturnSuccess,
              let pixelBuffer, let pixels = context.data else {
            throw StillVideoError.frame("no pixel buffer")
        }

        var range = vImage_YpCbCrPixelRange(Yp_bias: 16, CbCr_bias: 128, YpRangeMax: 235, CbCrRangeMax: 240, YpMax: 235, YpMin: 16, CbCrMax: 240, CbCrMin: 16)
        var conversion = vImage_ARGBToYpCbCr()
        guard vImageConvert_ARGBToYpCbCr_GenerateConversion(kvImage_ARGBToYpCbCrMatrix_ITU_R_709_2, &range, &conversion, kvImageARGB8888, kvImage420Yp8_CbCr8, vImage_Flags(kvImageNoFlags)) == kvImageNoError else {
            throw StillVideoError.frame("no YUV conversion")
        }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }
        var source = vImage_Buffer(data: pixels, height: vImagePixelCount(height), width: vImagePixelCount(width), rowBytes: context.bytesPerRow)
        var luma = vImage_Buffer(
            data: CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0), height: vImagePixelCount(height), width: vImagePixelCount(width),
            rowBytes: CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0))
        var chroma = vImage_Buffer(
            data: CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1), height: vImagePixelCount(height / 2), width: vImagePixelCount(width / 2),
            rowBytes: CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1))
        // The context is BGRA in memory; the converter reads ARGB.
        let bgraToArgb: [UInt8] = [3, 2, 1, 0]
        guard vImageConvert_ARGB8888To420Yp8_CbCr8(&source, &luma, &chroma, &conversion, bgraToArgb, vImage_Flags(kvImageNoFlags)) == kvImageNoError else {
            throw StillVideoError.frame("YUV conversion failed")
        }

        CVBufferSetAttachment(pixelBuffer, kCVImageBufferColorPrimariesKey, kCVImageBufferColorPrimaries_ITU_R_709_2, .shouldPropagate)
        CVBufferSetAttachment(pixelBuffer, kCVImageBufferTransferFunctionKey, kCVImageBufferTransferFunction_ITU_R_709_2, .shouldPropagate)
        CVBufferSetAttachment(pixelBuffer, kCVImageBufferYCbCrMatrixKey, kCVImageBufferYCbCrMatrix_ITU_R_709_2, .shouldPropagate)
        return pixelBuffer
    }

    // MARK: Video segment

    /// One keyframe followed by `frames - 1` frames that repeat it. No B-frames, so repeating
    /// the segment keeps timestamps in order.
    static func encodeSegment(_ frame: CVPixelBuffer, frames: Int, hd: Bool) throws -> [CMSampleBuffer] {
        var created: VTCompressionSession?
        var status = VTCompressionSessionCreate(
            allocator: nil, width: Int32(CVPixelBufferGetWidth(frame)), height: Int32(CVPixelBufferGetHeight(frame)),
            codecType: kCMVideoCodecType_H264, encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil, compressionSessionOut: &created)
        guard status == noErr, let session = created else {
            throw StillVideoError.encoder("Couldn't create the H.264 encoder", status)
        }
        defer { VTCompressionSessionInvalidate(session) }

        let properties: [CFString: Any] = [
            kVTCompressionPropertyKey_RealTime: false,
            kVTCompressionPropertyKey_ProfileLevel: kVTProfileLevel_H264_High_AutoLevel,
            // CAVLC, so H264RepeatFrames can write the repeats (CABAC would need its arithmetic coder).
            kVTCompressionPropertyKey_H264EntropyMode: kVTH264EntropyMode_CAVLC,
            kVTCompressionPropertyKey_AllowFrameReordering: false,
            kVTCompressionPropertyKey_MaxKeyFrameInterval: frames,
            kVTCompressionPropertyKey_ExpectedFrameRate: framesPerSecond,
            kVTCompressionPropertyKey_Quality: hd ? 0.75 : 0.65,
            kVTCompressionPropertyKey_ColorPrimaries: kCVImageBufferColorPrimaries_ITU_R_709_2,
            kVTCompressionPropertyKey_TransferFunction: kCVImageBufferTransferFunction_ITU_R_709_2,
            kVTCompressionPropertyKey_YCbCrMatrix: kCVImageBufferYCbCrMatrix_ITU_R_709_2,
        ]
        for (key, value) in properties {
            VTSessionSetProperty(session, key: key, value: value as CFTypeRef)
        }
        // A fixed QP like the ffmpeg path (24 HD / 28 SD) where the encoder offers one.
        var supportsFrameQP: CFTypeRef?
        _ = withUnsafeMutablePointer(to: &supportsFrameQP) {
            VTSessionCopyProperty(session, key: kVTCompressionPropertyKey_SupportsBaseFrameQP, allocator: nil, valueOut: UnsafeMutableRawPointer($0))
        }
        let frameQP: Int? = (supportsFrameQP as? Bool) == true ? (hd ? 24 : 28) : nil
        VTCompressionSessionPrepareToEncodeFrames(session)

        let collected = SegmentSamples(count: frames)
        func encode(_ index: Int) throws {
            var frameProperties: [CFString: Any] = [:]
            if index == 0 { frameProperties[kVTEncodeFrameOptionKey_ForceKeyFrame] = true }
            if let frameQP { frameProperties[kVTEncodeFrameOptionKey_BaseFrameQP] = index == 0 ? frameQP : 51 }
            let pts = CMTime(value: CMTimeValue(index), timescale: framesPerSecond)
            status = VTCompressionSessionEncodeFrame(
                session, imageBuffer: frame, presentationTimeStamp: pts,
                duration: CMTime(value: 1, timescale: framesPerSecond), frameProperties: frameProperties as CFDictionary, infoFlagsOut: nil
            ) { status, _, sample in
                collected.set(index, status: status, sample: sample)
            }
            guard status == noErr else { throw StillVideoError.encoder("Couldn't encode the video frame", status) }
            status = VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: pts)
            guard status == noErr else { throw StillVideoError.encoder("Couldn't finish the video frame", status) }
        }

        try encode(0)
        let keyframe = try collected.result(upTo: 1)[0]
        guard frames > 1 else { return [keyframe] }
        // The repeats only have to show the keyframe again. Encoders spend very different
        // amounts on that (the Simulator's fills its whole bitrate), so they are written
        // directly as P-frames whose macroblocks are all skipped: about 10 bytes each.
        if let repeats = H264RepeatFrames(keyframe: keyframe) {
            return try [keyframe] + (1..<frames).map { try repeats.frame($0) }
        }
        for index in 1..<frames { try encode(index) }
        return try collected.result(upTo: frames)
    }

    static func retime(_ sample: CMSampleBuffer, start: CMTime, duration: CMTime) throws -> CMSampleBuffer {
        var timing = CMSampleTimingInfo(duration: duration, presentationTimeStamp: start, decodeTimeStamp: start)
        var copy: CMSampleBuffer?
        let status = CMSampleBufferCreateCopyWithNewTiming(allocator: nil, sampleBuffer: sample, sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleBufferOut: &copy)
        guard status == noErr, let copy else { throw StillVideoError.encoder("Couldn't copy a video frame", status) }
        return copy
    }

    // MARK: Audio

    /// The highest AAC bitrate up to 128 kbps that the encoder accepts for this format (low
    /// sample rates cap it).
    static func aacBitRate(sampleRate: Double, channels: Int) -> Int {
        var input = AudioStreamBasicDescription(
            mSampleRate: sampleRate, mFormatID: kAudioFormatLinearPCM, mFormatFlags: kLinearPCMFormatFlagIsFloat | kLinearPCMFormatFlagIsPacked,
            mBytesPerPacket: UInt32(4 * channels), mFramesPerPacket: 1, mBytesPerFrame: UInt32(4 * channels),
            mChannelsPerFrame: UInt32(channels), mBitsPerChannel: 32, mReserved: 0)
        var output = AudioStreamBasicDescription(
            mSampleRate: sampleRate, mFormatID: kAudioFormatMPEG4AAC, mFormatFlags: 0, mBytesPerPacket: 0, mFramesPerPacket: 1024,
            mBytesPerFrame: 0, mChannelsPerFrame: UInt32(channels), mBitsPerChannel: 0, mReserved: 0)
        var created: AudioConverterRef?
        guard AudioConverterNew(&input, &output, &created) == noErr, let converter = created else { return audioBitRate }
        defer { AudioConverterDispose(converter) }
        var size: UInt32 = 0
        guard AudioConverterGetPropertyInfo(converter, kAudioConverterApplicableEncodeBitRates, &size, nil) == noErr, size > 0 else {
            return audioBitRate
        }
        var ranges = [AudioValueRange](repeating: AudioValueRange(), count: Int(size) / MemoryLayout<AudioValueRange>.size)
        guard AudioConverterGetProperty(converter, kAudioConverterApplicableEncodeBitRates, &size, &ranges) == noErr else {
            return audioBitRate
        }
        let rates = ranges.map { Int($0.mMaximum) }.filter { $0 > 0 }
        return rates.filter { $0 <= audioBitRate }.max() ?? rates.min() ?? audioBitRate
    }
}

/// Decodes the audio to float PCM at its own sample rate and channel count (up to stereo).
/// A speed change is a scaled composition edit rendered with the spectral time-pitch algorithm,
/// which changes the tempo and keeps the pitch.
private struct AudioSource {
    let reader: AVAssetReader
    let output: AVAssetReaderOutput
    let duration: CMTime
    let sampleRate: Double
    let channels: Int

    init(url: URL, speedFactor: Double) async throws {
        let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else { throw StillVideoError.noAudioTrack }
        let (timeRange, formats) = try await track.load(.timeRange, .formatDescriptions)
        let format = formats.first.flatMap { CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee }
        // AAC takes 8-48 kHz.
        sampleRate = min(max(format?.mSampleRate ?? 44_100, 8_000), 48_000)
        channels = min(max(Int(format?.mChannelsPerFrame ?? 1), 1), 2)
        let pcm: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
        ]

        if speedFactor > 0, abs(speedFactor - 1) > 0.001 {
            let composition = AVMutableComposition()
            guard let edit = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                throw StillVideoError.writer("Couldn't set up the speed change")
            }
            try edit.insertTimeRange(timeRange, of: track, at: .zero)
            let scaled = CMTimeMultiplyByFloat64(timeRange.duration, multiplier: 1 / speedFactor)
            edit.scaleTimeRange(CMTimeRange(start: .zero, duration: timeRange.duration), toDuration: scaled)
            let mix = AVAssetReaderAudioMixOutput(audioTracks: [edit], audioSettings: pcm)
            mix.audioTimePitchAlgorithm = .spectral
            reader = try AVAssetReader(asset: composition)
            output = mix
            duration = scaled
        } else {
            reader = try AVAssetReader(asset: asset)
            output = AVAssetReaderTrackOutput(track: track, outputSettings: pcm)
            duration = timeRange.duration
        }
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { throw StillVideoError.writer("Couldn't read the audio") }
        reader.add(output)
    }

    /// AAC-LC at the source's sample rate and channel count.
    func makeWriterInput() -> AVAssetWriterInput {
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
            AVEncoderBitRateKey: StillVideoEncoder.aacBitRate(sampleRate: sampleRate, channels: channels),
        ])
        input.expectsMediaDataInRealTime = false
        return input
    }

    /// Appends the decoded audio to `input` until it runs out, then finishes that input in `pump`.
    /// `progress` gets 0...1 in whole percents.
    func feed(_ input: AVAssetWriterInput, of writer: AVAssetWriter, pump: Pump, progress: @escaping (Double) -> Void) {
        var lastProgress = -1
        input.requestMediaDataWhenReady(on: DispatchQueue(label: "still-video.audio")) {
            while input.isReadyForMoreMediaData, !pump.isFinished {
                guard let sample = output.copyNextSampleBuffer() else {
                    if reader.status == .failed {
                        pump.fail(reader.error ?? StillVideoError.writer("Couldn't decode the audio"))
                    } else {
                        input.markAsFinished()
                        pump.inputFinished()
                    }
                    return
                }
                guard input.append(sample) else {
                    pump.fail(writer.error ?? StillVideoError.writer("Couldn't encode the audio"))
                    return
                }
                let end = CMTimeAdd(CMSampleBufferGetPresentationTimeStamp(sample), CMSampleBufferGetDuration(sample))
                let percent = Int(min(max(end.seconds / duration.seconds, 0), 1) * 100)
                if percent > lastProgress {
                    lastProgress = percent
                    progress(Double(percent) / 100)
                }
            }
        }
    }
}

/// Encoded segment frames, filled in by the encoder's callbacks.
private final class SegmentSamples: @unchecked Sendable {
    private let lock = NSLock()
    private var samples: [CMSampleBuffer?]
    private var status: OSStatus = noErr

    init(count: Int) { samples = Array(repeating: nil, count: count) }

    func set(_ index: Int, status: OSStatus, sample: CMSampleBuffer?) {
        lock.lock()
        defer { lock.unlock() }
        if status != noErr { self.status = status }
        samples[index] = sample
    }

    func result(upTo count: Int) throws -> [CMSampleBuffer] {
        lock.lock()
        defer { lock.unlock() }
        let encoded = samples.prefix(count).compactMap { $0 }
        guard status == noErr, encoded.count == count else {
            throw StillVideoError.encoder("The video encoder dropped frames", status)
        }
        return encoded
    }
}

/// Resumes once every input has finished, or on the first failure.
private final class Pump: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var pending = 0

    var isFinished: Bool {
        lock.lock()
        defer { lock.unlock() }
        return continuation == nil
    }

    func start(_ continuation: CheckedContinuation<Void, Error>, inputs: Int) {
        lock.lock()
        defer { lock.unlock() }
        self.continuation = continuation
        pending = inputs
    }

    func inputFinished() {
        lock.lock()
        pending -= 1
        let done = pending == 0 ? continuation : nil
        if done != nil { continuation = nil }
        lock.unlock()
        done?.resume()
    }

    func fail(_ error: Error) {
        lock.lock()
        let done = continuation
        continuation = nil
        lock.unlock()
        done?.resume(throwing: error)
    }
}

/// P-frames that repeat the previous frame exactly: one slice in which every macroblock is
/// skipped (P_Skip, with no residual, predicts the co-located block of the previous frame,
/// which for a still is a zero motion vector). Built against the keyframe's SPS and PPS; nil
/// when those use features this doesn't write (CABAC, weighted prediction, slice groups,
/// interlacing).
private struct H264RepeatFrames {
    private let formatDescription: CMFormatDescription
    private let lengthSize: Int
    private let ppsId: UInt32
    private let log2MaxFrameNum: Int
    private let pocType: UInt32
    private let log2MaxPocLsb: Int
    private let deltaPicOrderAlwaysZero: Bool
    private let bottomFieldPicOrderPresent: Bool
    private let redundantPicCntPresent: Bool
    private let deblockingFilterControlPresent: Bool
    private let macroblocks: UInt32

    init?(keyframe: CMSampleBuffer) {
        guard let format = CMSampleBufferGetFormatDescription(keyframe) else { return nil }
        var count = 0
        var lengthSize: Int32 = 0
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            format, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil,
            parameterSetCountOut: &count, nalUnitHeaderLengthOut: &lengthSize) == noErr, count >= 2,
              var sps = Self.parameterSet(format, 0).map(BitReader.init),
              var pps = Self.parameterSet(format, 1).map(BitReader.init) else { return nil }
        formatDescription = format
        self.lengthSize = Int(lengthSize)

        // SPS (7.3.2.1.1), after the NAL header.
        sps.skip(8)
        let profile = sps.bits(8)
        sps.skip(16) // constraint flags, level_idc
        _ = sps.ue() // seq_parameter_set_id
        if [100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135].contains(profile) {
            let chromaFormat = sps.ue()
            if chromaFormat == 3 { return nil }
            _ = sps.ue() // bit_depth_luma_minus8
            _ = sps.ue() // bit_depth_chroma_minus8
            sps.skip(1) // qpprime_y_zero_transform_bypass_flag
            if sps.flag() { // seq_scaling_matrix_present_flag
                for list in 0..<8 where sps.flag() {
                    var last = 8, next = 8
                    for _ in 0..<(list < 6 ? 16 : 64) where next != 0 {
                        next = (last + Int(sps.se()) + 256) % 256
                        last = next == 0 ? last : next
                    }
                }
            }
        }
        log2MaxFrameNum = Int(sps.ue()) + 4
        pocType = sps.ue()
        var log2MaxPocLsb = 0
        var deltaPicOrderAlwaysZero = false
        if pocType == 0 {
            log2MaxPocLsb = Int(sps.ue()) + 4
        } else if pocType == 1 {
            deltaPicOrderAlwaysZero = sps.flag()
            _ = sps.se() // offset_for_non_ref_pic
            _ = sps.se() // offset_for_top_to_bottom_field
            for _ in 0..<sps.ue() { _ = sps.se() }
        }
        self.log2MaxPocLsb = log2MaxPocLsb
        self.deltaPicOrderAlwaysZero = deltaPicOrderAlwaysZero
        _ = sps.ue() // max_num_ref_frames
        sps.skip(1) // gaps_in_frame_num_value_allowed_flag
        let widthInMbs = sps.ue() + 1
        let heightInMapUnits = sps.ue() + 1
        guard sps.flag() else { return nil } // frame_mbs_only_flag
        macroblocks = widthInMbs * heightInMapUnits

        // PPS (7.3.2.2).
        pps.skip(8)
        ppsId = pps.ue()
        _ = pps.ue() // seq_parameter_set_id
        guard !pps.flag() else { return nil } // entropy_coding_mode_flag: CABAC
        bottomFieldPicOrderPresent = pps.flag()
        guard pps.ue() == 0 else { return nil } // num_slice_groups_minus1
        _ = pps.ue() // num_ref_idx_l0_default_active_minus1
        _ = pps.ue() // num_ref_idx_l1_default_active_minus1
        guard !pps.flag() else { return nil } // weighted_pred_flag
        pps.skip(2) // weighted_bipred_idc
        _ = pps.se() // pic_init_qp_minus26
        _ = pps.se() // pic_init_qs_minus26
        _ = pps.se() // chroma_qp_index_offset
        deblockingFilterControlPresent = pps.flag()
        pps.skip(1) // constrained_intra_pred_flag
        redundantPicCntPresent = pps.flag()
        guard !sps.overrun, !pps.overrun, macroblocks > 0, log2MaxFrameNum <= 16, pocType <= 2 else { return nil }
    }

    /// The `index`th frame after the keyframe (index > 0).
    func frame(_ index: Int) throws -> CMSampleBuffer {
        var slice = BitWriter()
        slice.bits(0x21, 8) // forbidden_zero_bit 0, nal_ref_idc 1, nal_unit_type 1 (non-IDR slice)
        slice.ue(0) // first_mb_in_slice
        slice.ue(5) // slice_type: P, as are all slices of the picture
        slice.ue(ppsId)
        slice.bits(UInt32(index % (1 << log2MaxFrameNum)), log2MaxFrameNum) // frame_num
        if pocType == 0 {
            slice.bits(UInt32((2 * index) % (1 << log2MaxPocLsb)), log2MaxPocLsb) // pic_order_cnt_lsb
            if bottomFieldPicOrderPresent { slice.se(0) }
        } else if pocType == 1, !deltaPicOrderAlwaysZero {
            slice.se(0)
            if bottomFieldPicOrderPresent { slice.se(0) }
        }
        if redundantPicCntPresent { slice.ue(0) }
        slice.bits(1, 1) // num_ref_idx_active_override_flag
        slice.ue(0) // num_ref_idx_l0_active_minus1: only the previous frame
        slice.bits(0, 1) // ref_pic_list_modification_flag_l0
        slice.bits(0, 1) // adaptive_ref_pic_marking_mode_flag: sliding window
        slice.se(0) // slice_qp_delta
        if deblockingFilterControlPresent { slice.ue(1) } // disable_deblocking_filter_idc
        slice.ue(macroblocks) // mb_skip_run: the whole picture
        slice.bits(1, 1) // rbsp_stop_one_bit
        let nal = slice.escapedBytes()

        var sample = Data(count: lengthSize)
        for byte in 0..<lengthSize { sample[byte] = UInt8((nal.count >> (8 * (lengthSize - 1 - byte))) & 0xff) }
        sample.append(nal)

        var block: CMBlockBuffer?
        var status = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: sample.count, blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil, offsetToData: 0, dataLength: sample.count, flags: 0, blockBufferOut: &block)
        guard status == noErr, let block else { throw StillVideoError.encoder("Couldn't allocate a video frame", status) }
        status = sample.withUnsafeBytes { CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: sample.count) }
        guard status == noErr else { throw StillVideoError.encoder("Couldn't write a video frame", status) }

        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: StillVideoEncoder.framesPerSecond),
            presentationTimeStamp: CMTime(value: CMTimeValue(index), timescale: StillVideoEncoder.framesPerSecond),
            decodeTimeStamp: .invalid)
        var size = sample.count
        var buffer: CMSampleBuffer?
        status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: formatDescription, sampleCount: 1,
            sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &buffer)
        guard status == noErr, let buffer else { throw StillVideoError.encoder("Couldn't create a video frame", status) }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dictionary = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dictionary, Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(), Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
            CFDictionarySetValue(dictionary, Unmanaged.passUnretained(kCMSampleAttachmentKey_DependsOnOthers).toOpaque(), Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return buffer
    }

    private static func parameterSet(_ format: CMFormatDescription, _ index: Int) -> Data? {
        var pointer: UnsafePointer<UInt8>?
        var size = 0
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            format, parameterSetIndex: index, parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
            parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil) == noErr, let pointer else { return nil }
        return Data(bytes: pointer, count: size)
    }
}

/// Reads an RBSP: a NAL unit with its emulation prevention bytes removed.
private struct BitReader {
    private let bytes: [UInt8]
    private var position = 0
    private(set) var overrun = false

    init(_ nal: Data) {
        var bytes: [UInt8] = []
        var zeros = 0
        for byte in nal {
            if zeros >= 2, byte == 3 { zeros = 0; continue }
            zeros = byte == 0 ? zeros + 1 : 0
            bytes.append(byte)
        }
        self.bytes = bytes
    }

    mutating func bit() -> UInt32 {
        guard position < bytes.count * 8 else { overrun = true; return 0 }
        defer { position += 1 }
        return UInt32(bytes[position / 8] >> (7 - position % 8) & 1)
    }

    mutating func bits(_ count: Int) -> UInt32 { (0..<count).reduce(0) { value, _ in value << 1 | bit() } }
    mutating func skip(_ count: Int) { position += count }
    mutating func flag() -> Bool { bit() == 1 }

    mutating func ue() -> UInt32 {
        var zeros = 0
        while bit() == 0, !overrun, zeros < 32 { zeros += 1 }
        return (1 << zeros) - 1 + bits(zeros)
    }

    mutating func se() -> Int32 {
        let code = ue()
        return code % 2 == 1 ? Int32((code + 1) / 2) : -Int32(code / 2)
    }
}

/// Writes an RBSP and escapes it into a NAL unit.
private struct BitWriter {
    private var bytes: [UInt8] = []
    private var used = 0 // bits used in the last byte

    mutating func bits(_ value: UInt32, _ count: Int) {
        for shift in stride(from: count - 1, through: 0, by: -1) {
            if used == 0 { bytes.append(0) }
            bytes[bytes.count - 1] |= UInt8((value >> UInt32(shift)) & 1) << (7 - used)
            used = (used + 1) % 8
        }
    }

    mutating func ue(_ value: UInt32) {
        let code = UInt64(value) + 1
        let length = 64 - code.leadingZeroBitCount
        bits(0, length - 1)
        for shift in stride(from: length - 1, through: 0, by: -1) { bits(UInt32((code >> UInt64(shift)) & 1), 1) }
    }

    mutating func se(_ value: Int32) { ue(value > 0 ? UInt32(2 * value - 1) : UInt32(-2 * value)) }

    /// Byte-aligned (zero bits after the stop bit), with emulation prevention bytes.
    func escapedBytes() -> Data {
        var escaped = Data()
        var zeros = 0
        for (index, byte) in bytes.enumerated() {
            if index > 0, zeros >= 2, byte <= 3 { escaped.append(3); zeros = 0 }
            escaped.append(byte)
            zeros = byte == 0 ? zeros + 1 : 0
        }
        return escaped
    }
}
