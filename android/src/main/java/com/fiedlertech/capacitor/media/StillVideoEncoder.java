package com.fiedlertech.capacitor.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Mp4Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultAssetLoaderFactory;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EncoderSelector;
import androidx.media3.transformer.EncoderUtil;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.InAppMp4Muxer;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Share videos: one still frame plus a generation's audio.
 *
 * <p>Mirrors StillVideoEncoder.swift and buildStillSegmentArgs / buildStillVideoMuxArgs in the app's
 * src/services/stillVideo.ts. The frame is composed once and Media3 Transformer encodes it into a
 * short H.264 segment (a keyframe followed by frames that repeat it), while AacTranscoder encodes
 * the audio. The segment's samples are then copied for the length of the audio, so the video costs
 * the same for any length; only the audio is encoded in full.
 */
@OptIn(markerClass = UnstableApi.class)
final class StillVideoEncoder {

    static final int FRAMES_PER_SECOND = 1;
    /** Length of the repeated segment, which is also the keyframe interval. */
    static final int SEGMENT_SECONDS = 10;
    static final int AUDIO_BIT_RATE = 128_000;
    private static final int BACKGROUND = 0xFF13161D;
    /** Media3's is 10 s, short for a slow phone that encodes the audio at the same time. */
    private static final long MAX_DELAY_BETWEEN_SAMPLES_MS = 20_000;

    /** Android's own H.264 encoders, for phones whose hardware encoder fails on the segment. */
    private static final EncoderSelector SOFTWARE_ENCODERS = mimeType -> ImmutableList.copyOf(Iterables.filter(
            EncoderUtil.getSupportedEncoders(mimeType), info -> !EncoderUtil.isHardwareAccelerated(info, mimeType)));

    interface ProgressListener {
        /** 0...1. */
        void onProgress(double progress);
    }

    static final class Options {
        File audio;
        @Nullable Future<byte[]> image;
        /** Watermark artwork, authored at 9:16. */
        @Nullable Future<byte[]> overlay;
        /** Even, because the video is 4:2:0. */
        int width;
        int height;
        boolean hd;
        /** Tempo change that keeps the pitch, like ffmpeg's atempo. */
        double speedFactor = 1;
        File output;
    }

    private final Context context;
    /** Transformer's application thread: it's built, started and polled there. */
    private final Handler handler;
    private final ExecutorService executor;
    private volatile boolean warmedUp;
    /** Set once a hardware export fails: the rest of the process encodes in software. */
    private volatile boolean softwareEncoder;

    StillVideoEncoder(Context context, Looper looper, ExecutorService executor) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(looper);
        this.executor = executor;
    }

    /** Writes the video and returns its duration in seconds. */
    double encode(Options options, ProgressListener progress) throws Exception {
        int width = options.width;
        int height = options.height;
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw new IOException("Invalid video size " + width + "x" + height);
        }

        File muxed = tempFile(".mp4");
        Future<Segment> segmentEncode = null;
        try {
            // A message shorter than a segment needs only as many frames as it lasts.
            double seconds = durationUs(options.audio, "audio/") / 1e6 / speed(options.speedFactor);
            int segmentFrames = Math.max(1, Math.min(SEGMENT_SECONDS * FRAMES_PER_SECOND, (int) Math.round(seconds * FRAMES_PER_SECOND)));

            // The frame is composed and encoded while this thread encodes the audio, the long part.
            segmentEncode = executor.submit(() -> {
                Bitmap image = options.image == null ? null : decodeImage(await(options.image), width, height);
                Bitmap overlay = options.overlay == null ? null : decodeImage(await(options.overlay), 0, height);
                return encodeSegment(composeFrame(width, height, image, overlay), segmentFrames, options.hd);
            });
            EncodedAudio audio = new EncodedAudio();
            long durationUs = AacTranscoder.transcode(options.audio, options.speedFactor, AUDIO_BIT_RATE, audio, progress);
            Segment segment = await(segmentEncode);

            mux(segment, segmentFrames, audio, durationUs, muxed);
            Mp4FastStart.write(muxed, options.output);
            progress.onProgress(1);
            return durationUs / 1e6;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            options.output.delete();
            throw e;
        } finally {
            if (segmentEncode != null) segmentEncode.cancel(false);
            //noinspection ResultOfMethodCallIgnored
            muxed.delete();
        }
    }

    /**
     * Writes the audio alone at another tempo, like ffmpeg's atempo, as AAC-LC in an .m4a (as on
     * iOS), and returns its duration in seconds.
     */
    double encodeAudio(File source, double speedFactor, File output) throws Exception {
        File muxed = tempFile(".m4a");
        try {
            long durationUs;
            Mp4Muxer muxer = new Mp4Muxer.Builder(new FileOutputStream(muxed)).setAttemptStreamableOutputEnabled(false).build();
            try {
                int[] track = { -1 };
                durationUs = AacTranscoder.transcode(source, speedFactor, AUDIO_BIT_RATE, new AacTranscoder.Output() {
                    @Override
                    public void onFormat(MediaFormat format) throws IOException {
                        try {
                            track[0] = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(format));
                        } catch (MuxerException e) {
                            throw new IOException("Couldn't write the audio: " + e.getMessage(), e);
                        }
                    }

                    @Override
                    public void onSample(ByteBuffer sample, long timeUs) throws IOException {
                        try {
                            muxer.writeSampleData(track[0], sample, new BufferInfo(timeUs, sample.remaining(), C.BUFFER_FLAG_KEY_FRAME));
                        } catch (MuxerException e) {
                            throw new IOException("Couldn't write the audio: " + e.getMessage(), e);
                        }
                    }
                }, null);
            } finally {
                muxer.close();
            }
            Mp4FastStart.write(muxed, output);
            return durationUs / 1e6;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw e;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            muxed.delete();
        }
    }

    /**
     * The first export in a process loads Media3's classes, the codec list, an encoder and a GL
     * context. A throwaway one-frame export made while the inputs download moves that cost out of
     * the real one.
     */
    void warmUp() {
        if (warmedUp) return;
        warmedUp = true;
        File output = null;
        try {
            output = tempFile(".mp4");
            Bitmap frame = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            frame.eraseColor(BACKGROUND);
            await(exportSegment(frame, 1, false, softwareEncoder, output));
        } catch (Exception e) {
            warmedUp = false;
            // The real export would fail the same way: it goes straight to software.
            if (e instanceof ExportException) softwareEncoder = true;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            if (output != null) output.delete();
        }
    }

    // Frame

    /**
     * Decodes WebP, JPEG, PNG and the like, upright (EXIF orientation applied), subsampled while
     * it still covers {@code coverWidth} x {@code coverHeight}.
     */
    static Bitmap decodeImage(byte[] data, int coverWidth, int coverHeight) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Couldn't compose the video frame: unreadable image");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(new ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) {
            // No EXIF (WebP, PNG): already upright.
        }
        boolean transposed = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
        int width = transposed ? bounds.outHeight : bounds.outWidth;
        int height = transposed ? bounds.outWidth : bounds.outHeight;
        double scale = Math.max((double) coverWidth / width, (double) coverHeight / height);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (width / (options.inSampleSize * 2) >= Math.ceil(width * scale) && height / (options.inSampleSize * 2) >= Math.ceil(height * scale)) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("Couldn't compose the video frame: undecodable image");

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
            default: return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * #13161d canvas, the voice image cover-fitted (scaled up to fill, center crop) and the
     * watermark at full height, centered.
     */
    static Bitmap composeFrame(int width, int height, @Nullable Bitmap image, @Nullable Bitmap overlay) {
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        canvas.drawColor(BACKGROUND);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        if (image != null) {
            float scale = Math.max((float) width / image.getWidth(), (float) height / image.getHeight());
            float drawnWidth = image.getWidth() * scale;
            float drawnHeight = image.getHeight() * scale;
            canvas.drawBitmap(image, null, new RectF((width - drawnWidth) / 2, (height - drawnHeight) / 2, (width + drawnWidth) / 2, (height + drawnHeight) / 2), paint);
        }
        if (overlay != null) {
            // ffmpeg's scale=-2:H: the aspect-correct width, rounded to an even number.
            int overlayWidth = 2 * (int) Math.round((double) height * overlay.getWidth() / overlay.getHeight() / 2);
            canvas.drawBitmap(overlay, null, new RectF((width - overlayWidth) / 2f, 0, (width + overlayWidth) / 2f, height), paint);
        }
        return frame;
    }

    // Exports

    /** The segment's H.264 frames, from Transformer's export. */
    private Segment encodeSegment(Bitmap frame, int frames, boolean hd) throws Exception {
        File file = tempFile(".mp4");
        try {
            try {
                await(exportSegment(frame, frames, hd, softwareEncoder, file));
            } catch (ExportException e) {
                if (softwareEncoder) throw e;
                // Hardware encoders of some Android 8-11 phones stall on the segment until Media3's
                // muxer watchdog ends the export. Android's software encoder copes with these few frames.
                softwareEncoder = true;
                await(exportSegment(frame, frames, hd, true, file));
            }
            return readSegment(file);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /**
     * The frame shown for {@code frames} seconds at 1 fps: one keyframe, then frames that only
     * repeat it. Portrait frames may come out rotated to landscape, with the rotation in the track.
     */
    private ListenableFuture<ExportResult> exportSegment(Bitmap frame, int frames, boolean hd, boolean software, File output) {
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(Uri.parse("stillvideo://frame"))
                .setMimeType(MimeTypes.IMAGE_PNG)
                .setImageDurationMs(frames * 1000L / FRAMES_PER_SECOND)
                .build();
        EditedMediaItem item = new EditedMediaItem.Builder(mediaItem).setFrameRate(FRAMES_PER_SECOND).build();
        VideoEncoderSettings settings = new VideoEncoderSettings.Builder()
                .setBitrate(videoBitRate(frame.getWidth(), frame.getHeight(), hd))
                // Past the segment's end, so the encoder adds no keyframe of its own (the software
                // encoder of Android 10 put one at 9 s with an interval of 10 s).
                .setiFrameIntervalSeconds(2 * SEGMENT_SECONDS)
                .build();
        Transformer.Builder transformer = new Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAssetLoaderFactory(new DefaultAssetLoaderFactory(context, new FrameLoader(frame)))
                .setEncoderFactory(new DefaultEncoderFactory.Builder(context)
                        .setVideoEncoderSelector(software ? SOFTWARE_ENCODERS : EncoderSelector.DEFAULT)
                        .setRequestedVideoEncoderSettings(settings)
                        .build())
                .setMuxerFactory(new InAppMp4Muxer.Factory().setAttemptStreamableOutputEnabled(false))
                .setMaxDelayBetweenMuxerSamplesMs(MAX_DELAY_BETWEEN_SAMPLES_MS);
        return export(transformer, item, output);
    }

    /**
     * Encoder bitrate for the segment. Per 10 s it buys about the bytes x264 spends on the same
     * frame in the ffmpeg path (QP 24 HD / 28 SD), most of them on the keyframe.
     */
    static int videoBitRate(int width, int height, boolean hd) {
        return (int) Math.round(width * (double) height * (hd ? 0.08 : 0.11));
    }

    private ListenableFuture<ExportResult> export(Transformer.Builder builder, EditedMediaItem item, File output) {
        SettableFuture<ExportResult> result = SettableFuture.create();
        handler.post(() -> {
            if (result.isDone()) return;
            try {
                Transformer transformer = builder
                        .setLooper(handler.getLooper())
                        .addListener(new Transformer.Listener() {
                            @Override
                            public void onCompleted(Composition composition, ExportResult exportResult) {
                                result.set(exportResult);
                            }

                            @Override
                            public void onError(Composition composition, ExportResult exportResult, ExportException exportException) {
                                result.setException(exportException);
                            }
                        })
                        .build();
                transformer.start(item, output.getAbsolutePath());
                result.addListener(() -> {
                    if (result.isCancelled()) transformer.cancel();
                }, handler::post);
            } catch (RuntimeException e) {
                result.setException(e);
            }
        });
        return result;
    }

    // Mux

    /** The encoder's AAC frames, kept in memory (about 1 MB a minute) until the segment is ready. */
    private static final class EncodedAudio implements AacTranscoder.Output {
        MediaFormat format;
        final List<Sample> samples = new ArrayList<>();

        @Override
        public void onFormat(MediaFormat format) {
            this.format = format;
        }

        @Override
        public void onSample(ByteBuffer sample, long timeUs) {
            byte[] data = new byte[sample.remaining()];
            sample.get(data);
            samples.add(new Sample(data, timeUs, true));
        }
    }

    /** An encoded segment: its format (with the SPS and PPS) and its frames, from its start. */
    private static final class Segment {
        MediaFormat format;
        final List<Sample> frames = new ArrayList<>();
    }

    private static final class Sample {
        final byte[] data;
        final long timeUs;
        final boolean keyframe;

        Sample(byte[] data, long timeUs, boolean keyframe) {
            this.data = data;
            this.timeUs = timeUs;
            this.keyframe = keyframe;
        }
    }

    private static Segment readSegment(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            Segment segment = new Segment();
            segment.format = extractor.getTrackFormat(selectTrack(extractor, "video/"));
            ByteBuffer buffer = ByteBuffer.allocate(maxInputSize(segment.format, 1 << 20));
            long firstFrameUs = -1;
            while (true) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                if (firstFrameUs < 0) firstFrameUs = extractor.getSampleTime();
                byte[] data = new byte[size];
                buffer.get(data, 0, size);
                segment.frames.add(new Sample(data, extractor.getSampleTime() - firstFrameUs, (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0));
                extractor.advance();
            }
            return segment;
        } finally {
            extractor.release();
        }
    }

    /**
     * Writes the segment's frames, repeated for {@code durationUs}, and the audio into one MP4. The
     * last frame lasts until the audio ends, so a 60.003 s message doesn't end on a 3 ms frame.
     */
    private static void mux(Segment segment, int segmentFrames, EncodedAudio audio, long durationUs, File output) throws IOException {
        if (audio.format == null || audio.samples.isEmpty()) throw new IOException("The audio encoder wrote nothing");
        List<Sample> frames = segment.frames;
        if (frames.isEmpty() || !frames.get(0).keyframe) throw new IOException("The video encoder wrote no keyframe");
        MediaFormat videoFormat = segment.format;
        Mp4Muxer muxer = null;
        try {
            // The last frame takes the remainder, as on iOS.
            long frameCount = Math.max(1, Math.round(durationUs / 1e6 * FRAMES_PER_SECOND));
            long endOfFramesUs = frameCount * C.MICROS_PER_SECOND / FRAMES_PER_SECOND;
            long segmentUs = (long) segmentFrames * C.MICROS_PER_SECOND / FRAMES_PER_SECOND;

            muxer = new Mp4Muxer.Builder(new FileOutputStream(output)).setAttemptStreamableOutputEnabled(false).build();
            int videoId = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(videoFormat));
            int audioId = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(audio.format));
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.addMetadataEntry(new Mp4OrientationData(videoFormat.getInteger(MediaFormat.KEY_ROTATION)));
            }

            // Interleaved by time: the segment's frames shifted by whole segments, and the audio.
            int repeat = 0;
            int frameIndex = 0;
            long frameUs = 0;
            int audioIndex = 0;
            while (true) {
                boolean framesLeft = frameUs < endOfFramesUs;
                boolean audioLeft = audioIndex < audio.samples.size();
                if (!framesLeft && !audioLeft) break;
                if (framesLeft && (!audioLeft || frameUs <= audio.samples.get(audioIndex).timeUs)) {
                    Sample frame = frames.get(frameIndex);
                    muxer.writeSampleData(videoId, ByteBuffer.wrap(frame.data), new BufferInfo(frameUs, frame.data.length, frame.keyframe ? C.BUFFER_FLAG_KEY_FRAME : 0));
                    if (++frameIndex == frames.size()) {
                        frameIndex = 0;
                        repeat++;
                    }
                    frameUs = repeat * segmentUs + frames.get(frameIndex).timeUs;
                } else {
                    Sample sample = audio.samples.get(audioIndex++);
                    muxer.writeSampleData(audioId, ByteBuffer.wrap(sample.data), new BufferInfo(sample.timeUs, sample.data.length, C.BUFFER_FLAG_KEY_FRAME));
                }
            }
            muxer.writeSampleData(videoId, ByteBuffer.allocate(0), new BufferInfo(durationUs, 0, C.BUFFER_FLAG_END_OF_STREAM));
            muxer.close();
            muxer = null;
        } catch (MuxerException e) {
            throw new IOException("Couldn't write the video: " + e.getMessage(), e);
        } finally {
            if (muxer != null) {
                try {
                    muxer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // Helpers

    /** The first track of the given kind ("audio/", "video/"), selected. */
    static int selectTrack(MediaExtractor extractor, String kind) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(kind)) {
                extractor.selectTrack(i);
                return i;
            }
        }
        throw new IOException(kind.startsWith("audio") ? "The audio file has no audio track" : "The video has no video track");
    }

    private static int maxInputSize(MediaFormat format, int fallback) {
        return format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(fallback, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : fallback;
    }

    /** The track's duration as the container states it (MP3: from its headers). */
    static long durationUs(File file, String kind) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat format = extractor.getTrackFormat(selectTrack(extractor, kind));
            return format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
        } finally {
            extractor.release();
        }
    }

    private static double speed(double speedFactor) {
        return speedFactor > 0 ? speedFactor : 1;
    }

    private File tempFile(String suffix) throws IOException {
        return File.createTempFile("still-video-", suffix, context.getCacheDir());
    }

    private static <T> T await(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    /** Hands the composed frame to Transformer's image input, whatever its URI. */
    private static final class FrameLoader implements BitmapLoader {
        private final Bitmap frame;

        FrameLoader(Bitmap frame) {
            this.frame = frame;
        }

        @Override
        public boolean supportsMimeType(String mimeType) {
            return true;
        }

        @Override
        public ListenableFuture<Bitmap> decodeBitmap(byte[] data) {
            return Futures.immediateFuture(frame);
        }

        @Override
        public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
            return Futures.immediateFuture(frame);
        }
    }
}
