package com.fiedlertech.capacitor.media;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes the share video's segment with MediaCodec directly: the frame is converted to YUV once
 * and queued as byte buffers, with no GL and no input surface.
 *
 * <p>For the phones (Android 7-11, mostly low-end) where Transformer's export stalls until Media3's
 * muxer watchdog ends it, with their hardware and software encoders alike.
 */
final class BufferSegmentEncoder {

    /** An encoder that takes no input and gives no output for this long is given up for the next. */
    private static final long STALL_MS = 8_000;
    private static final long DEQUEUE_TIMEOUT_US = 10_000;

    private BufferSegmentEncoder() {
    }

    /** The frame shown for {@code frames} seconds at 1 fps: one keyframe, then frames that repeat it. */
    static StillVideoEncoder.Segment encode(Bitmap frame, int frames, int bitRate) throws IOException {
        List<String> failures = new ArrayList<>();
        for (MediaCodecInfo info : encoders()) {
            MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
            int width = frame.getWidth();
            int height = frame.getHeight();
            // As Transformer does: a portrait size some encoders lack is encoded as landscape and
            // rotated back by the track's rotation.
            boolean rotate = !video.isSizeSupported(width, height) && video.isSizeSupported(height, width);
            if (!rotate && !video.isSizeSupported(width, height)) {
                failures.add(info.getName() + ": no " + width + "x" + height);
                continue;
            }
            try {
                StillVideoEncoder.Segment segment = encode(info.getName(), rotate ? rotated(frame) : frame, frames, bitRate);
                if (rotate) segment.format.setInteger(MediaFormat.KEY_ROTATION, 270);
                return segment;
            } catch (IOException | RuntimeException e) {
                failures.add(info.getName() + ": " + e);
            }
        }
        throw new IOException("No encoder wrote the video" + (failures.isEmpty() ? ": no H.264 encoder takes YUV buffers" : " (" + TextUtils.join("; ", failures) + ")"));
    }

    /**
     * H.264 encoders that take flexible YUV buffers, software first: Android's own encoder takes
     * buffers on every phone, and this path only runs where an export already failed.
     */
    private static List<MediaCodecInfo> encoders() {
        List<MediaCodecInfo> software = new ArrayList<>();
        List<MediaCodecInfo> hardware = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || (Build.VERSION.SDK_INT >= 29 && info.isAlias())) continue;
            boolean avc = false;
            for (String type : info.getSupportedTypes()) avc |= type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC);
            if (!avc) continue;
            boolean flexible = false;
            for (int color : info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats) {
                flexible |= color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
            }
            if (!flexible) continue;
            (isSoftware(info) ? software : hardware).add(info);
        }
        software.addAll(hardware);
        return software;
    }

    private static boolean isSoftware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly();
        String name = info.getName();
        return name.startsWith("OMX.google.") || name.startsWith("c2.android.");
    }

    private static StillVideoEncoder.Segment encode(String codecName, Bitmap frame, int frames, int bitRate) throws IOException {
        Yuv yuv = new Yuv(frame);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, yuv.width, yuv.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // 30 fps and VBR, as Transformer configures it. At the true 1 fps the encoder spends a
        // second's bits on each repeat of the frame: the video track came out almost three times
        // the size.
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        // Past the segment's end, as in the Transformer export.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2 * StillVideoEncoder.SEGMENT_SECONDS);
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);

        MediaCodec codec = MediaCodec.createByCodecName(codecName);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            StillVideoEncoder.Segment segment = new StillVideoEncoder.Segment();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // Frames queued, then one more input for the end of stream.
            int queued = 0;
            long firstFrameUs = -1;
            long progressAt = SystemClock.elapsedRealtime();
            while (true) {
                if (queued <= frames) {
                    int index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (index >= 0) {
                        long timeUs = (long) queued * 1_000_000 / StillVideoEncoder.FRAMES_PER_SECOND;
                        if (queued < frames) {
                            Image image = codec.getInputImage(index);
                            if (image == null) throw new IOException("no input image");
                            yuv.write(image);
                            codec.queueInputBuffer(index, 0, yuv.width * yuv.height * 3 / 2, timeUs, 0);
                        } else {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                        queued++;
                        progressAt = SystemClock.elapsedRealtime();
                    }
                }

                int index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    segment.format = codec.getOutputFormat();
                    progressAt = SystemClock.elapsedRealtime();
                } else if (index >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        byte[] data = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.get(data);
                        if (firstFrameUs < 0) firstFrameUs = info.presentationTimeUs;
                        segment.frames.add(new StillVideoEncoder.Sample(data, info.presentationTimeUs - firstFrameUs, (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0));
                    }
                    codec.releaseOutputBuffer(index, false);
                    progressAt = SystemClock.elapsedRealtime();
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }

                if (SystemClock.elapsedRealtime() - progressAt > STALL_MS) {
                    throw new IOException("stalled after " + segment.frames.size() + " of " + frames + " frames");
                }
            }

            if (segment.format == null || !segment.format.containsKey("csd-0")) throw new IOException("no SPS/PPS");
            if (segment.frames.size() != frames) throw new IOException("wrote " + segment.frames.size() + " of " + frames + " frames");
            return segment;
        } finally {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // Not started, or already failed.
            }
            codec.release();
        }
    }

    /** Turned a quarter clockwise; the track's rotation of 270 turns it back. */
    private static Bitmap rotated(Bitmap frame) {
        Matrix matrix = new Matrix();
        matrix.setRotate(90);
        return Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), frame.getHeight(), matrix, true);
    }

    /** The frame in YUV 4:2:0, BT.709 limited range, converted once and written into each input. */
    private static final class Yuv {
        final int width;
        final int height;
        private final byte[] y;
        private final byte[] u;
        private final byte[] v;

        Yuv(Bitmap frame) {
            width = frame.getWidth();
            height = frame.getHeight();
            int[] argb = new int[width * height];
            frame.getPixels(argb, 0, width, 0, 0, width, height);
            y = new byte[width * height];
            u = new byte[width * height / 4];
            v = new byte[width * height / 4];
            for (int i = 0; i < argb.length; i++) {
                int c = argb[i];
                y[i] = (byte) ((47 * ((c >> 16) & 0xFF) + 157 * ((c >> 8) & 0xFF) + 16 * (c & 0xFF) + 4224) >> 8);
            }
            // Chroma from the mean of each 2x2 block.
            int chromaWidth = width / 2;
            for (int row = 0; row < height / 2; row++) {
                for (int col = 0; col < chromaWidth; col++) {
                    int i = 2 * row * width + 2 * col;
                    int c0 = argb[i], c1 = argb[i + 1], c2 = argb[i + width], c3 = argb[i + width + 1];
                    int r = ((c0 >> 16) & 0xFF) + ((c1 >> 16) & 0xFF) + ((c2 >> 16) & 0xFF) + ((c3 >> 16) & 0xFF);
                    int g = ((c0 >> 8) & 0xFF) + ((c1 >> 8) & 0xFF) + ((c2 >> 8) & 0xFF) + ((c3 >> 8) & 0xFF);
                    int b = (c0 & 0xFF) + (c1 & 0xFF) + (c2 & 0xFF) + (c3 & 0xFF);
                    u[row * chromaWidth + col] = (byte) ((-26 * r - 87 * g + 112 * b + 4 * 32896) >> 10);
                    v[row * chromaWidth + col] = (byte) ((112 * r - 102 * g - 10 * b + 4 * 32896) >> 10);
                }
            }
        }

        void write(Image image) {
            Image.Plane[] planes = image.getPlanes();
            write(planes[0], y, width, height);
            write(planes[1], u, width / 2, height / 2);
            write(planes[2], v, width / 2, height / 2);
        }

        private static void write(Image.Plane plane, byte[] data, int width, int height) {
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            for (int row = 0; row < height; row++) {
                int start = row * rowStride;
                if (pixelStride == 1) {
                    buffer.position(start);
                    buffer.put(data, row * width, width);
                } else {
                    for (int col = 0; col < width; col++) buffer.put(start + col * pixelStride, data[row * width + col]);
                }
            }
        }
    }
}
