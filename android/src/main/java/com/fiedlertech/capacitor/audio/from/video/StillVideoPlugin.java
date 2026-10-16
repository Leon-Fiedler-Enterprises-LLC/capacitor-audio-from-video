package com.fiedlertech.capacitor.audio.from.video;

import android.net.Uri;
import android.os.HandlerThread;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Share videos, and shared audio at another playback speed, on Android: StillVideoEncoder behind
 * Capacitor calls, with the same methods, options and results as StillVideoPlugin.swift. The app
 * keeps ffmpeg.wasm for the web and for builds without this plugin.
 */
@CapacitorPlugin(name = "StillVideo")
public class StillVideoPlugin extends Plugin {

    private static final List<String> AUDIO_EXTENSIONS = Arrays.asList("mp3", "m4a", "aac", "wav", "aif", "aiff", "caf", "mp4", "m4v", "mov", "ogg", "opus", "webm", "flac");
    private static final String LOCAL_FILE_PREFIX = "/_capacitor_file_";

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Prefetch prefetch = new Prefetch();
    private HandlerThread encoderThread;
    private StillVideoEncoder encoder;

    @Override
    public void load() {
        encoderThread = new HandlerThread("StillVideo");
        encoderThread.start();
        encoder = new StillVideoEncoder(getContext(), encoderThread.getLooper(), executor);
    }

    @Override
    protected void handleOnDestroy() {
        executor.shutdown();
        if (encoderThread != null) encoderThread.quitSafely();
    }

    /**
     * Starts the downloads and the encoder while the share sheet is open. The audio download alone
     * (a signed CDN URL) takes longer than the encode of a short message.
     */
    @PluginMethod
    public void prepare(PluginCall call) {
        String audio = call.getString("audioUrl");
        String[] images = { call.getString("imageUrl"), call.getString("overlayUrl") };
        prefetch.replace(audio != null && isRemote(audio) ? audio : null, images);
        executor.execute(encoder::warmUp);
        call.resolve();
    }

    @PluginMethod
    public void createStillVideo(PluginCall call) {
        String audio = call.getString("audioUrl");
        File output = localFile(call.getString("outputPath"));
        String image = call.getString("imageUrl");
        if (audio == null || output == null || image == null) {
            call.reject("audioUrl, outputPath and imageUrl are required");
            return;
        }
        String overlay = call.getString("overlayUrl");
        StillVideoEncoder.Options options = new StillVideoEncoder.Options();
        options.width = call.getInt("width", 0);
        options.height = call.getInt("height", 0);
        options.hd = call.getBoolean("hd", false);
        options.speedFactor = call.getDouble("speedFactor", 1.0);
        options.output = output;

        executor.execute(() -> {
            AudioFile audioFile = null;
            try {
                // The images download (or come from prepare()) while the audio is encoded.
                options.image = imageData(image);
                options.overlay = overlay == null ? null : imageData(overlay);
                audioFile = audioFile(audio);
                options.audio = audioFile.file;
                double duration = encoder.encode(options, progress -> {
                    JSObject event = new JSObject();
                    event.put("progress", progress);
                    notifyListeners("progress", event);
                });
                JSObject result = new JSObject();
                result.put("path", Uri.fromFile(output).toString());
                result.put("duration", duration);
                call.resolve(result);
            } catch (Exception e) {
                call.reject(message(e), e);
            } finally {
                if (audioFile != null && audioFile.temporary) {
                    //noinspection ResultOfMethodCallIgnored
                    audioFile.file.delete();
                }
            }
        });
    }

    /** The audio at another tempo with the pitch kept, as AAC in an .m4a, as on iOS. */
    @PluginMethod
    public void changeAudioSpeed(PluginCall call) {
        String audio = call.getString("audioUrl");
        File output = localFile(call.getString("outputPath"));
        if (audio == null || output == null) {
            call.reject("audioUrl and outputPath are required");
            return;
        }
        double speedFactor = call.getDouble("speedFactor", 1.0);

        executor.execute(() -> {
            AudioFile audioFile = null;
            try {
                audioFile = audioFile(audio);
                double duration = encoder.encodeAudio(audioFile.file, speedFactor, output);
                JSObject result = new JSObject();
                result.put("path", Uri.fromFile(output).toString());
                result.put("duration", duration);
                call.resolve(result);
            } catch (Exception e) {
                call.reject(message(e), e);
            } finally {
                if (audioFile != null && audioFile.temporary) {
                    //noinspection ResultOfMethodCallIgnored
                    audioFile.file.delete();
                }
            }
        });
    }

    private static final class AudioFile {
        final File file;
        /** Downloaded for this call only. */
        final boolean temporary;

        AudioFile(File file, boolean temporary) {
            this.file = file;
            this.temporary = temporary;
        }
    }

    /**
     * The audio as a local file: the file itself, prepare()'s download (kept for another video of
     * the same message until the next prepare()), or a new download.
     */
    private AudioFile audioFile(String source) throws Exception {
        File local = localFile(source);
        if (local != null) return new AudioFile(local, false);
        Future<File> prefetched = prefetch.audio(source);
        if (prefetched != null) {
            try {
                return new AudioFile(prefetched.get(), false);
            } catch (ExecutionException ignored) {
                // Downloads again below, and reports that failure.
            }
        }
        return new AudioFile(downloadAudio(source), true);
    }

    /** prepare()'s download, or a new one (also when that download failed). */
    private Future<byte[]> imageData(String source) {
        Future<byte[]> prefetched = prefetch.image(source);
        if (prefetched == null) return executor.submit(() -> data(source));
        return executor.submit(() -> {
            try {
                return prefetched.get();
            } catch (ExecutionException e) {
                return data(source);
            }
        });
    }

    /** Downloads into the cache directory, keeping the file's extension. */
    private File downloadAudio(String source) throws IOException {
        if (!isRemote(source)) throw new IOException("Unsupported audio URL");
        String extension = "";
        String path = Uri.parse(source).getLastPathSegment();
        if (path != null && path.lastIndexOf('.') >= 0) {
            String candidate = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (AUDIO_EXTENSIONS.contains(candidate)) extension = candidate;
        }
        File file = new File(getContext().getCacheDir(), "still-video-" + UUID.randomUUID() + "." + (extension.isEmpty() ? "mp3" : extension));
        HttpURLConnection connection = open(source);
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(file)) {
            copy(in, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
        return file;
    }

    /** https URLs, data URLs, file URLs and paths, and the WebView's local URLs. */
    private byte[] data(String source) throws IOException {
        if (source.startsWith("data:")) {
            int comma = source.indexOf(',');
            if (comma < 0 || !source.substring(0, comma).endsWith(";base64")) throw new IOException("Couldn't compose the video frame: unsupported data URL");
            return Base64.decode(source.substring(comma + 1), Base64.DEFAULT);
        }
        File local = localFile(source);
        if (local != null) {
            try (InputStream in = new FileInputStream(local)) {
                return readAll(in);
            }
        }
        String asset = bundledAsset(source);
        if (asset != null) {
            try (InputStream in = getContext().getAssets().open(asset)) {
                return readAll(in);
            }
        }
        if (!isRemote(source)) throw new IOException("Couldn't compose the video frame: unsupported image URL");
        HttpURLConnection connection = open(source);
        try (InputStream in = connection.getInputStream()) {
            return readAll(in);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String source) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("Download failed (" + status + ")");
        }
        return connection;
    }

    private boolean isRemote(String source) {
        return (source.startsWith("https://") || source.startsWith("http://")) && !isLocalServer(Uri.parse(source));
    }

    /** The WebView's own origin (https://localhost by default), which is served from the app, not the network. */
    private boolean isLocalServer(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        String localHost = bridge != null ? Uri.parse(bridge.getLocalUrl()).getHost() : null;
        return host.equals(localHost) || host.equals("localhost");
    }

    /** file:// URLs, absolute paths, and Capacitor.convertFileSrc URLs (https://localhost/_capacitor_file_/...). */
    @Nullable
    private File localFile(@Nullable String source) {
        if (source == null) return null;
        if (source.startsWith("/")) return new File(source);
        Uri uri = Uri.parse(source);
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath());
        String path = uri.getPath();
        if (isLocalServer(uri) && path != null && path.startsWith(LOCAL_FILE_PREFIX + "/")) {
            return new File(path.substring(LOCAL_FILE_PREFIX.length()));
        }
        return null;
    }

    /** The web bundle's files (https://localhost/images/...), which live in the APK's assets. */
    @Nullable
    private String bundledAsset(String source) {
        Uri uri = Uri.parse(source);
        String path = uri.getPath();
        if (!isLocalServer(uri) || path == null || path.length() < 2) return null;
        return "public" + path;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    private static String message(Exception e) {
        Throwable error = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    /** Downloads started by prepare(), for the message whose share sheet is open. */
    private final class Prefetch {
        @Nullable private String audioSource;
        @Nullable private Future<File> audioDownload;
        private Map<String, Future<byte[]>> imageDownloads = new HashMap<>();

        /** Keeps downloads that are still wanted (the same sheet opened again) and drops the rest. */
        synchronized void replace(@Nullable String audio, String[] images) {
            if (!Objects.equals(audioSource, audio)) {
                Future<File> previous = audioDownload;
                audioSource = audio;
                audioDownload = audio == null ? null : executor.submit(() -> downloadAudio(audio));
                if (previous != null) {
                    executor.execute(() -> {
                        try {
                            //noinspection ResultOfMethodCallIgnored
                            previous.get().delete();
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
            Map<String, Future<byte[]>> next = new HashMap<>();
            for (String image : images) {
                if (image == null || next.containsKey(image)) continue;
                Future<byte[]> download = imageDownloads.get(image);
                next.put(image, download != null ? download : executor.submit(() -> data(image)));
            }
            imageDownloads = next;
        }

        @Nullable
        synchronized Future<File> audio(String source) {
            return source.equals(audioSource) ? audioDownload : null;
        }

        @Nullable
        synchronized Future<byte[]> image(String source) {
            return imageDownloads.get(source);
        }
    }
}
