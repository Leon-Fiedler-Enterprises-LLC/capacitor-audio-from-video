package com.fiedlertech.capacitor.audio.from.video;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
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

        // Handle content URIs
        if ("content".equals(uri.getScheme())) {
            try {
                Cursor cursor = resolver.query(uri, new String[]{android.provider.MediaStore.Images.ImageColumns.DATA}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    String filePath = cursor.getString(0);
                    cursor.close();
                    if (filePath != null) {
                        return new File(filePath);
                    } else {
                        // Fallback: copy the content to a temporary file
                        InputStream is = resolver.openInputStream(uri);
                        if(is != null) {
                            File tempFile = File.createTempFile("audio_temp", ".tmp");
                            FileOutputStream fos = new FileOutputStream(tempFile);
                            byte[] buffer = new byte[4096];
                            int read;
                            while ((read = is.read(buffer)) != -1) {
                                fos.write(buffer, 0, read);
                            }
                            fos.close();
                            is.close();
                            return tempFile;
                        }
                    }
                } else if(cursor != null) {
                    cursor.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting file path from content URI", e);
            }
            return null;
        }

        // Handle file URIs or raw paths
        if (uri.getScheme() == null || "file".equals(uri.getScheme())) {
            String filePath = uri.getPath();
            return filePath != null ? new File(filePath) : null;
        }

        return null;
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

            // 2) Determine duration & 5-minute cap
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION)
                    : 0L;
            final long MAX_DURATION_US = 300L * 1_000_000L; // 5 min
            long cutoffUs = (durationUs > 0) ? Math.min(durationUs, MAX_DURATION_US) : MAX_DURATION_US;

            // 3) Configure decoder (from input track)
            final String inMime = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(inMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            // 4) Configure encoder (AAC LC), match sample rate/channel count to input
            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channelCount = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            MediaFormat outputFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC); // optional but recommended
            outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024);

            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // 5) Prepare muxer (track added after encoder signals format)
            muxer = new MediaMuxer(outputAudioFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrackIndex = -1;
            boolean muxerStarted = false;

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
                        // Copy PCM to encoder input
                        int encInIndex = encoder.dequeueInputBuffer(10_000);
                        if (encInIndex >= 0) {
                            ByteBuffer encIn = encoder.getInputBuffer(encInIndex);
                            if (encIn != null) {
                                encIn.clear();
                                // Ensure we only put the valid range
                                decOut.position(decInfo.offset);
                                decOut.limit(decInfo.offset + decInfo.size);
                                encIn.put(decOut);

                                lastPtsUs = decInfo.presentationTimeUs;
                                encoder.queueInputBuffer(encInIndex, 0, decInfo.size,
                                        decInfo.presentationTimeUs, 0);
                                fedEncoderThisLoop = true;

                                // Progress callback
                                if (durationUs > 0) {
                                    double p = Math.min(1.0,
                                            (double) lastPtsUs / (double) Math.max(1, durationUs));
                                    try {
                                        callback.onExtractionProgress(p);
                                    } catch (Exception ignored) {}
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

                // Drain encoder -> mux
                while (true) {
                    int encOutIndex = encoder.dequeueOutputBuffer(encInfo, 10_000);
                    if (encOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            // Shouldn’t happen twice
                            throw new IllegalStateException("Encoder output format changed twice");
                        }
                        MediaFormat newFormat = encoder.getOutputFormat();
                        muxerTrackIndex = muxer.addTrack(newFormat);
                        muxer.start();
                        muxerStarted = true;
                    } else if (encOutIndex >= 0) {
                        ByteBuffer encOut = encoder.getOutputBuffer(encOutIndex);
                        if (encOut != null && encInfo.size > 0) {
                            if (!muxerStarted) {
                                // Waited for format change but didn't get it?
                                throw new IllegalStateException("Muxer has not started");
                            }
                            encOut.position(encInfo.offset);
                            encOut.limit(encInfo.offset + encInfo.size);
                            muxer.writeSampleData(muxerTrackIndex, encOut, encInfo);
                        }

                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderEOS = true;
                        }
                        encoder.releaseOutputBuffer(encOutIndex, false);
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

            callback.onExtractionCompleted(outputAudioFile, "audio/mp4");

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
        }
    }).start();
}

}