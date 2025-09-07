package com.fiedlertech.capacitor.audio.from.video;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.MediaCodec;
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
            try {
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(videoFile.getAbsolutePath());

                int audioTrackIndex = -1;
                MediaFormat audioFormat = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i;
                        audioFormat = format;
                        break;
                    }
                }

                if (audioTrackIndex == -1) {
                    callback.onExtractionFailed("No audio track found");
                    return;
                }

                extractor.selectTrack(audioTrackIndex);

                // Set up output format: AAC, 44.1kHz, stereo, 128kbps
                MediaFormat outputFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
                outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
                outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);

                MediaCodec decoder = MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME));
                decoder.configure(audioFormat, null, null, 0);
                decoder.start();

                MediaCodec encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();

                MediaMuxer muxer = new MediaMuxer(outputAudioFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int muxerTrackIndex = muxer.addTrack(outputFormat);
                muxer.start();

                ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
                ByteBuffer[] decoderOutputBuffers = decoder.getOutputBuffers();
                ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
                ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                long duration = audioFormat.containsKey(MediaFormat.KEY_DURATION) ? audioFormat.getLong(MediaFormat.KEY_DURATION) : 0;
                long maxDuration = 300 * 1000000L; // 300 seconds in microseconds
                long currentTime = 0;

                boolean inputDone = false;
                boolean decoderDone = false;
                boolean encoderDone = false;

                while (!encoderDone) {
                    // Feed decoder input
                    if (!inputDone) {
                        int inputIndex = decoder.dequeueInputBuffer(-1);
                        if (inputIndex >= 0) {
                            ByteBuffer inputBuffer = decoderInputBuffers[inputIndex];
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0 || currentTime >= maxDuration) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long sampleTime = extractor.getSampleTime();
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0);
                                extractor.advance();
                                currentTime = sampleTime;
                            }
                        }
                    }

                    // Decode
                    if (!decoderDone) {
                        int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
                        if (outputIndex >= 0) {
                            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decoderDone = true;
                            }
                            boolean doRender = (bufferInfo.size != 0);
                            decoder.releaseOutputBuffer(outputIndex, doRender);
                            if (doRender) {
                                // Feed encoder
                                int encInputIndex = encoder.dequeueInputBuffer(-1);
                                if (encInputIndex >= 0) {
                                    ByteBuffer encInputBuffer = encoderInputBuffers[encInputIndex];
                                    encInputBuffer.clear();
                                    encInputBuffer.put(decoderOutputBuffers[outputIndex]);
                                    encoder.queueInputBuffer(encInputIndex, 0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags);
                                }
                            }
                        } else if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            decoderOutputBuffers = decoder.getOutputBuffers();
                        }
                    }

                    // Encode and mux
                    int encOutputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                    if (encOutputIndex >= 0) {
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                        }
                        if (bufferInfo.size > 0) {
                            ByteBuffer encOutputBuffer = encoderOutputBuffers[encOutputIndex];
                            encOutputBuffer.position(bufferInfo.offset);
                            encOutputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(muxerTrackIndex, encOutputBuffer, bufferInfo);

                            // Progress
                            double progress = (double) bufferInfo.presentationTimeUs / duration;
                            callback.onExtractionProgress(progress);
                        }
                        encoder.releaseOutputBuffer(encOutputIndex, false);
                    } else if (encOutputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        encoderOutputBuffers = encoder.getOutputBuffers();
                    }
                }

                extractor.release();
                decoder.stop();
                decoder.release();
                encoder.stop();
                encoder.release();
                muxer.stop();
                muxer.release();

                callback.onExtractionCompleted(outputAudioFile, "audio/mp4");
            } catch (Exception e) {
                Log.e(TAG, "Extraction failed", e);
                callback.onExtractionFailed(e.getMessage());
            }
        }).start();
    }
}