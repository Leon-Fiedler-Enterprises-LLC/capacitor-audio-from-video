package com.bazumax.capacitor.audio.from.video;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import android.util.Base64;
import android.util.Log;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.LogCallback;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.SessionState;
import com.arthenica.ffmpegkit.Statistics;
import com.arthenica.ffmpegkit.StatisticsCallback;

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
    public void extractAudio(File videoFile, File outputAudioFile, ExtractionCallback callback) {

        if (outputAudioFile.exists()) {
            outputAudioFile.delete();
        }


        String videoFilePath = videoFile.getAbsolutePath();
        String outputAudioFilePath = outputAudioFile.getAbsolutePath();

        // Create an FFmpeg session with the parameters for extraction of audio from video file
        String[] cmd = {
                "-i", escapePath(videoFilePath),
                "-t", "300",
                "-vn", "-ar", "44100", "-ac", "2", "-b:a", "128k",
                escapePath(outputAudioFilePath)
        };

        String command = String.join(" ", cmd);
        FFmpegKit.executeAsync(command, new FFmpegSessionCompleteCallback() {

            @Override
            public void apply(FFmpegSession session) {
                SessionState state = session.getState();
                ReturnCode returnCode = session.getReturnCode();

                // CALLED WHEN SESSION IS EXECUTED

                Log.d(TAG, String.format("FFmpeg process exited with state %s and rc %s.%s", state, returnCode, session.getFailStackTrace()));

                try {
                    //TODO: Mimetype extract from ext
                    callback.onExtractionCompleted(outputAudioFile, "video/mp4");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new LogCallback() {

            @Override
            public void apply(com.arthenica.ffmpegkit.Log log) {

                // CALLED WHEN SESSION PRINTS LOGS

                Log.d(TAG, log.getMessage());
            }
        }, new StatisticsCallback() {

            @Override
            public void apply(Statistics statistics) {
                // CALLED WHEN SESSION GENERATES STATISTICS
                statistics.getTime();
            }
        });
    }



    public void compressVideo(File videoFile, File outputAudioFile, int width, int height, int bitrate, long durationInMillis,  ExtractionCallback callback) {

        if (outputAudioFile.exists()) {
            outputAudioFile.delete();
        }


        String videoFilePath = videoFile.getAbsolutePath();
        String outputVideoFilePath = outputAudioFile.getAbsolutePath();

        // Create an FFmpeg session with the parameters for extraction of audio from video file
        String[] cmd = {
                "-i", escapePath(videoFilePath),
                "-b:v", (bitrate/1000) + "k", "-vf", "scale=" + width +":" + height,
                "-c:v", "libx264",
                escapePath(outputVideoFilePath)
        };

        String command = String.join(" ", cmd);
        FFmpegKit.executeAsync(command, new FFmpegSessionCompleteCallback() {

            @Override
            public void apply(FFmpegSession session) {
                SessionState state = session.getState();
                ReturnCode returnCode = session.getReturnCode();

                // CALLED WHEN SESSION IS EXECUTED

                Log.d(TAG, String.format("FFmpeg process exited with state %s and rc %s.%s", state, returnCode, session.getFailStackTrace()));

                try {
                    callback.onExtractionCompleted(outputAudioFile, "audio/mp4");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new LogCallback() {

            @Override
            public void apply(com.arthenica.ffmpegkit.Log log) {

                // CALLED WHEN SESSION PRINTS LOGS

                Log.d(TAG, log.getMessage());
            }
        }, new StatisticsCallback() {

            @Override
            public void apply(Statistics statistics) {
                // CALLED WHEN SESSION GENERATES STATISTICS
                callback.onExtractionProgress(statistics.getTime() / durationInMillis );
            }
        });
    }
}