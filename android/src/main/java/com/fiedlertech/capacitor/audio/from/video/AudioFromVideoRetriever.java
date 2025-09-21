package com.fiedlertech.capacitor.audio.from.video;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.RequiresApi;

public class AudioFromVideoRetriever {

    public interface ExtractionCallback {
        void onExtractionCompleted(File file, String mimeType) throws IOException;
        void onExtractionFailed(String errorMessage);
        void onExtractionProgress(Double progress);
    }

    public File getFileObject(String path, ContentResolver resolver) {
        if (path == null) {
            return null;
        }

        Uri uri = Uri.parse(path);
        if (uri == null) {
            return null;
        }

        // Handle content URIs: robustly copy to a temp file to support scoped storage
        if ("content".equals(uri.getScheme())) {
            InputStream is = null;
            FileOutputStream fos = null;
            try {
                String mime = resolver.getType(uri);
                String ext = mimeToExt(mime);
                File tempFile = File.createTempFile("voicesai_afv_src_", ext);
                is = resolver.openInputStream(uri);
                if (is == null) {
                    return null;
                }
                fos = new FileOutputStream(tempFile);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.flush();
                return tempFile;
            } catch (Exception e) {
                Log.e(TAG, "Error copying content URI to temp file", e);
                return null;
            } finally {
                try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                try { if (is != null) is.close(); } catch (Exception ignored) {}
            }
        }

        // Handle file URIs or raw paths
        if (uri.getScheme() == null || "file".equals(uri.getScheme())) {
            String filePath = uri.getPath();
            return filePath != null ? new File(filePath) : null;
        }

        return null;
    }

    private static String mimeToExt(String mime) {
        if (mime == null) return ".tmp";
        try {
            if (mime.equals("audio/mpeg")) return ".mp3";
            if (mime.equals("audio/mp4") || mime.equals("audio/aac") || mime.equals("audio/mp4a-latm")) return ".m4a";
            if (mime.equals("audio/ogg")) return ".ogg";
            if (mime.equals("audio/webm") || mime.equals("video/webm")) return ".webm";
            if (mime.startsWith("video/")) return ".mp4";
        } catch (Exception ignored) {}
        return ".tmp";
    }

    public String getDataUrlFromAudioFile(File file, String mimeType) throws IOException {
        InputStream inputStream = new FileInputStream(file);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }

        byte[] audioData = outputStream.toByteArray();

        String base64Data = Base64.encodeToString(audioData, Base64.DEFAULT);
        return "data:" + mimeType + ";base64," + base64Data;
    }

    private static final String TAG = "VideoToAudio";

    private static String escapePath(String path) {
        return "\"" + path.replace("\"", "\\\"") + "\"";
    }

    private static boolean isEncoderAvailable(String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                MediaCodecInfo[] infos = list.getCodecInfos();
                for (MediaCodecInfo info : infos) {
                    if (!info.isEncoder()) continue;
                    String[] types = info.getSupportedTypes();
                    for (String t : types) {
                        if (t.equalsIgnoreCase(mimeType)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static int computeTargetBitrateForMp3(int channels) {
        // Standardize to 192 kbps while staying under 10 MB for 5 minutes
        return 192_000;
    }

    private static int computeTargetBitrateForAac(int channels) {
        // Match standard 192 kbps for consistency with MP3
        return 192_000;
    }

    // Downmix PCM 16-bit (assumes decoder output format is PCM 16-bit) to given channel count (1 or 2)
    // decOut: PCM 16-bit interleaved, inputChannels>=outputChannels, encIn destination buffer
    private static int downmixPcmToChannels(ByteBuffer decOut, int inputChannels, int outputChannels, ByteBuffer encIn) {
        // We can only proceed if both buffers are direct and have arrays or we can use ShortBuffers
        int samples = decOut.remaining() / 2; // 16-bit
        ShortBuffer inSb = decOut.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        ShortBuffer outSb = encIn.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int frames = samples / Math.max(1, inputChannels);
        int maxOutFrames = outSb.remaining() / Math.max(1, outputChannels);
        int framesToProcess = Math.min(frames, maxOutFrames);

        // Temporary arrays can help speed but allocate-free approach via buffers is fine
        for (int f = 0; f < framesToProcess; f++) {
            int base = f * inputChannels;
            if (outputChannels == 1) {
                // Mono: average first two channels if present, otherwise first channel
                int left = inSb.get(base);
                int right = (inputChannels >= 2) ? inSb.get(base + 1) : left;
                short mono = (short) ((left + right) / 2);
                outSb.put(mono);
            } else { // stereo
                int left = inSb.get(base);
                int right = (inputChannels >= 2) ? inSb.get(base + 1) : left;
                outSb.put((short) left);
                outSb.put((short) right);
            }
        }

        // Advance source buffer position accordingly (bytes)
        int bytesConsumed = framesToProcess * inputChannels * 2;
        decOut.position(decOut.position() + bytesConsumed);
        // Return bytes written to encIn
        return framesToProcess * outputChannels * 2;
    }

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public void extractAudio(File videoFile, File outputAudioFile, ExtractionCallback callback) {
    if (outputAudioFile.exists()) {
        outputAudioFile.delete();
    }

    new Thread(() -> {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        FileOutputStream rawOutput = null; // for MP3 raw stream

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(videoFile.getAbsolutePath());

            // 1) Pick the audio track
            int audioTrackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    inputFormat = fmt;
                    break;
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) {
                callback.onExtractionFailed("No audio track found");
                return;
            }
            extractor.selectTrack(audioTrackIndex);

            // 2) Determine duration & 5-minute cap and 10 MB size cap
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION)
                    : 0L;
            final long MAX_DURATION_US = 300L * 1_000_000L; // 5 min
            long cutoffUs = (durationUs > 0) ? Math.min(durationUs, MAX_DURATION_US) : MAX_DURATION_US;
            final long MAX_SIZE_BYTES = 10L * 1024L * 1024L; // 10 MB

            // Fast-path: if input is already MP3, just pass-through frames up to caps
            final String sourceMime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (sourceMime != null && sourceMime.equals("audio/mpeg")) {
                try {
                    rawOutput = new FileOutputStream(outputAudioFile);
                    long bytesWritten = 0L;
                    long lastPtsUs = 0L;
                    ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);

                    while (true) {
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleSize < 0 || (sampleTimeUs >= cutoffUs && cutoffUs > 0) || bytesWritten >= MAX_SIZE_BYTES) {
                            break;
                        }
                        rawOutput.write(buffer.array(), 0, sampleSize);
                        bytesWritten += sampleSize;
                        lastPtsUs = sampleTimeUs;
                        if (durationUs > 0) {
                            double p = Math.min(1.0, (double) lastPtsUs / (double) Math.max(1, durationUs));
                            try { callback.onExtractionProgress(p); } catch (Exception ignored) {}
                        }
                        extractor.advance();
                    }
                    try { rawOutput.flush(); } catch (Exception ignored) {}
                    try { rawOutput.close(); } catch (Exception ignored) { rawOutput = null; }
                    rawOutput = null;

                    callback.onExtractionCompleted(outputAudioFile, "audio/mpeg");
                    // Cleanup and return
                    if (extractor != null) extractor.release();
                    return;
                } catch (Exception passthroughEx) {
                    Log.w(TAG, "MP3 pass-through failed, falling back to re-encode", passthroughEx);
                    try { if (rawOutput != null) rawOutput.close(); } catch (Exception ignored) {}
                    rawOutput = null;
                    extractor.unselectTrack(audioTrackIndex);
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    extractor.selectTrack(audioTrackIndex);
                }
            }

            // Fast-path: if input is AAC, remux to M4A without re-encoding (very fast)
            if (sourceMime != null && sourceMime.equals("audio/mp4a-latm")) {
                MediaMuxer fastMuxer = null;
                try {
                    fastMuxer = new MediaMuxer(outputAudioFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    int outTrack = fastMuxer.addTrack(inputFormat);
                    fastMuxer.start();

                    int maxIn = inputFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ?
                            inputFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : (256 * 1024);
                    ByteBuffer buffer = ByteBuffer.allocate(Math.max(64 * 1024, maxIn));
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                    long bytesWritten = 0L;
                    long lastPtsUs = 0L;

                    while (true) {
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleSize < 0 || (sampleTimeUs >= cutoffUs && cutoffUs > 0) || bytesWritten >= MAX_SIZE_BYTES) {
                            break;
                        }
                        info.offset = 0;
                        info.size = sampleSize;
                        info.presentationTimeUs = sampleTimeUs;
                        info.flags = extractor.getSampleFlags();
                        buffer.position(0);
                        buffer.limit(sampleSize);
                        fastMuxer.writeSampleData(outTrack, buffer, info);
                        bytesWritten += sampleSize;
                        lastPtsUs = sampleTimeUs;
                        if (durationUs > 0) {
                            double p = Math.min(1.0, (double) lastPtsUs / (double) Math.max(1, durationUs));
                            try { callback.onExtractionProgress(p); } catch (Exception ignored) {}
                        }
                        extractor.advance();
                    }

                    try { fastMuxer.stop(); } catch (Exception ignored) {}
                    try { fastMuxer.release(); } catch (Exception ignored) {}

                    if (extractor != null) extractor.release();
                    callback.onExtractionCompleted(outputAudioFile, "audio/mp4");
                    return;
                } catch (Exception passEx) {
                    Log.w(TAG, "AAC remux fast-path failed, falling back to decode/encode", passEx);
                    try { if (fastMuxer != null) fastMuxer.release(); } catch (Exception ignored) {}
                    extractor.unselectTrack(audioTrackIndex);
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    extractor.selectTrack(audioTrackIndex);
                }
            }

            // 3) Configure decoder (from input track)
            final String inMime = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(inMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            // 4) Configure encoder (prefer MP3 if available), match sample rate and downmix to <=2 channels
            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int inputChannelCount = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int outputChannelCount = Math.max(1, Math.min(2, inputChannelCount));

            boolean wantMp3 = isEncoderAvailable("audio/mpeg");
            boolean usedMp3 = false;
            String outMime;
            MediaFormat outputFormat;
            int targetBitrate;
            try {
                if (wantMp3) {
                    outMime = "audio/mpeg";
                    outputFormat = MediaFormat.createAudioFormat(outMime, sampleRate, outputChannelCount);
                    targetBitrate = computeTargetBitrateForMp3(outputChannelCount);
                    outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate);
                    outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024);
                    encoder = MediaCodec.createEncoderByType(outMime);
                    encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    encoder.start();
                    usedMp3 = true;
                } else {
                    throw new IllegalStateException("MP3 encoder not available");
                }
            } catch (Exception encEx) {
                Log.w(TAG, "Using AAC encoder due to MP3 unavailability/failure", encEx);
                // Fallback to AAC
                outMime = "audio/mp4a-latm";
                outputFormat = MediaFormat.createAudioFormat(outMime, sampleRate, outputChannelCount);
                targetBitrate = computeTargetBitrateForAac(outputChannelCount);
                outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate);
                outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024);
                try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
                encoder = MediaCodec.createEncoderByType(outMime);
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();
                usedMp3 = false;
            }

            // 5) Prepare output: muxer for AAC, raw file for MP3
            int muxerTrackIndex = -1;
            boolean muxerStarted = false;
            long totalBytesWritten = 0L;
            if (usedMp3) {
                rawOutput = new FileOutputStream(outputAudioFile);
            } else {
                muxer = new MediaMuxer(outputAudioFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }

            // 6) Loop state
            boolean extractorEOS = false;
            boolean decoderEOS = false;
            boolean encoderEOS = false;

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            long lastPtsUs = 0;

            while (!encoderEOS) {

                // Feed decoder
                if (!extractorEOS) {
                    int inIndex = decoder.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIndex);
                        if (inBuf == null) {
                            // Shouldn't happen, skip this cycle
                        } else {
                            inBuf.clear();
                            int sampleSize = extractor.readSampleData(inBuf, 0);
                            long sampleTimeUs = extractor.getSampleTime();

                            if (sampleSize < 0 || (sampleTimeUs >= cutoffUs && cutoffUs > 0)) {
                                // End of input
                                decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                extractorEOS = true;
                            } else {
                                int flags = extractor.getSampleFlags();
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, flags);
                                extractor.advance();
                            }
                        }
                    }
                }

                // Drain decoder -> feed encoder
                boolean fedEncoderThisLoop = false;
                int outIndex = decoder.dequeueOutputBuffer(decInfo, 10_000);
                if (outIndex >= 0) {
                    ByteBuffer decOut = decoder.getOutputBuffer(outIndex);
                    if ((decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderEOS = true;
                    }

                    if (decOut != null && decInfo.size > 0) {
                        // Copy PCM to encoder input with optional downmix
                        int encInIndex = encoder.dequeueInputBuffer(10_000);
                        if (encInIndex >= 0) {
                            ByteBuffer encIn = encoder.getInputBuffer(encInIndex);
                            if (encIn != null) {
                                encIn.clear();
                                decOut.limit(decInfo.offset + decInfo.size);
                                decOut.position(decInfo.offset);

                                int bytesQueued;
                                if (inputChannelCount == outputChannelCount) {
                                    // Direct copy
                                    int copySize = Math.min(decInfo.size, encIn.capacity());
                                    int oldLimit = decOut.limit();
                                    decOut.limit(decInfo.offset + copySize);
                                    encIn.put(decOut);
                                    decOut.limit(oldLimit);
                                    bytesQueued = copySize;
                                } else {
                                    // Downmix >2 channels to stereo
                                    bytesQueued = downmixPcmToChannels(decOut, inputChannelCount, outputChannelCount, encIn);
                                }

                                lastPtsUs = decInfo.presentationTimeUs;
                                encoder.queueInputBuffer(encInIndex, 0, bytesQueued,
                                        decInfo.presentationTimeUs, 0);
                                fedEncoderThisLoop = true;

                                // Progress callback
                                if (durationUs > 0) {
                                    double p = Math.min(1.0,
                                            (double) lastPtsUs / (double) Math.max(1, durationUs));
                                    try { callback.onExtractionProgress(p); } catch (Exception ignored) {}
                                }
                            }
                        } // if no encoder buffer now, we’ll try next loop without losing decOut
                    }

                    // IMPORTANT: release decoder buffer AFTER copying
                    decoder.releaseOutputBuffer(outIndex, /*render*/ false);
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Decoder format change (rare for audio), ignore for PCM copy pipeline
                }

                // If decoder reached EOS and we haven't queued EOS to encoder yet, do it
                if (decoderEOS) {
                    int encInIndex = encoder.dequeueInputBuffer(10_000);
                    if (encInIndex >= 0) {
                        encoder.queueInputBuffer(encInIndex, 0, 0, lastPtsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }

                // Drain encoder -> write
                while (true) {
                    int encOutIndex = encoder.dequeueOutputBuffer(encInfo, 10_000);
                    if (encOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (usedMp3) {
                            // MP3 raw stream: no muxer format change expected
                        } else {
                            if (muxerStarted) {
                                throw new IllegalStateException("Encoder output format changed twice");
                            }
                            MediaFormat newFormat = encoder.getOutputFormat();
                            muxerTrackIndex = muxer.addTrack(newFormat);
                            muxer.start();
                            muxerStarted = true;
                        }
                    } else if (encOutIndex >= 0) {
                        ByteBuffer encOut = encoder.getOutputBuffer(encOutIndex);
                        if (encOut != null && encInfo.size > 0) {
                            encOut.position(encInfo.offset);
                            encOut.limit(encInfo.offset + encInfo.size);
                            if (usedMp3) {
                                // Write raw MP3 frame data
                                byte[] chunk = new byte[encInfo.size];
                                encOut.get(chunk);
                                rawOutput.write(chunk);
                                totalBytesWritten += encInfo.size;
                            } else {
                                if (!muxerStarted) {
                                    throw new IllegalStateException("Muxer has not started");
                                }
                                muxer.writeSampleData(muxerTrackIndex, encOut, encInfo);
                                totalBytesWritten += encInfo.size;
                            }
                        }

                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderEOS = true;
                        }
                        encoder.releaseOutputBuffer(encOutIndex, false);

                        // Enforce 10 MB cap
                        if (totalBytesWritten >= MAX_SIZE_BYTES) {
                            // Signal EOS to encoder and break
                            int encInIndex = encoder.dequeueInputBuffer(0);
                            if (encInIndex >= 0) {
                                encoder.queueInputBuffer(encInIndex, 0, 0, lastPtsUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            }
                            break;
                        }
                    }
                }
            }

            // Cleanup
            if (extractor != null) extractor.release();
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                decoder.release();
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                encoder.release();
            }
            if (muxer != null) {
                try { muxer.stop(); } catch (Exception ignored) {}
                muxer.release();
            }
            if (rawOutput != null) {
                try { rawOutput.flush(); } catch (Exception ignored) {}
                try { rawOutput.close(); } catch (Exception ignored) {}
            }

            callback.onExtractionCompleted(outputAudioFile, usedMp3 ? "audio/mpeg" : "audio/mp4");

        } catch (Exception e) {
            Log.e(TAG, "Extraction failed", e);
            try {
                callback.onExtractionFailed(e.getMessage());
            } catch (Exception ignored) {}
            // Best-effort cleanup
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
            try { if (decoder != null) decoder.release(); } catch (Exception ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
            try { if (rawOutput != null) rawOutput.close(); } catch (Exception ignored) {}
        }
    }).start();
}

}