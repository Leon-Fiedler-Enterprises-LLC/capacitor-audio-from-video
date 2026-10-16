package com.fiedlertech.capacitor.media;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Moves an MP4's moov box ahead of its media data, like ffmpeg's -movflags +faststart and
 * AVAssetWriter's shouldOptimizeForNetworkUse, so players and uploads can start before reading the
 * whole file. Media3's muxer would instead reserve 400 KB up front for it.
 */
final class Mp4FastStart {

    private static final List<String> CONTAINERS = Arrays.asList("trak", "mdia", "minf", "stbl");

    private Mp4FastStart() {}

    /** Copies {@code input} to {@code output} (replaced if it exists) with the moov box first. */
    static void write(File input, File output) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(input, "r"); FileOutputStream out = new FileOutputStream(output)) {
            FileChannel source = in.getChannel();
            FileChannel target = out.getChannel();
            List<long[]> boxes = topLevelBoxes(in);

            long[] moov = null;
            long[] mdat = null;
            for (long[] box : boxes) {
                if (box[2] == type("moov") && moov == null) moov = box;
                if (box[2] == type("mdat") && mdat == null) mdat = box;
            }
            if (moov == null) throw new IOException("Not an MP4: no moov box");

            ByteBuffer moovBox = null;
            if (mdat != null && moov[0] > mdat[0] && moov[1] < Integer.MAX_VALUE) {
                moovBox = ByteBuffer.allocate((int) moov[1]);
                while (moovBox.hasRemaining()) {
                    if (source.read(moovBox, moov[0] + moovBox.position()) < 0) throw new IOException("Truncated moov box");
                }
                moovBox.flip();
                if (!shiftChunkOffsets(moovBox, 8, moovBox.limit(), mdat[0], moov[1])) moovBox = null;
            }

            for (long[] box : boxes) {
                if (moovBox != null && box == moov) continue;
                if (moovBox != null && box == mdat) {
                    moovBox.rewind();
                    while (moovBox.hasRemaining()) target.write(moovBox);
                }
                long copied = 0;
                while (copied < box[1]) copied += source.transferTo(box[0] + copied, box[1] - copied, target);
            }
        }
    }

    /** {offset, size, type} of each top-level box. */
    private static List<long[]> topLevelBoxes(RandomAccessFile in) throws IOException {
        List<long[]> boxes = new ArrayList<>();
        long length = in.length();
        long offset = 0;
        while (offset + 8 <= length) {
            in.seek(offset);
            long size = in.readInt() & 0xFFFFFFFFL;
            int type = in.readInt();
            if (size == 1) size = in.readLong();
            else if (size == 0) size = length - offset;
            if (size < 8 || offset + size > length) throw new IOException("Malformed MP4 box at " + offset);
            boxes.add(new long[] { offset, size, type });
            offset += size;
        }
        return boxes;
    }

    /**
     * Adds {@code delta} to every chunk offset at or after {@code from} in the stco / co64 boxes
     * under moov. False when a 32-bit offset would overflow (the file then keeps its layout).
     */
    private static boolean shiftChunkOffsets(ByteBuffer moov, int start, int end, long from, long delta) {
        int offset = start;
        while (offset + 8 <= end) {
            long size = moov.getInt(offset) & 0xFFFFFFFFL;
            int type = moov.getInt(offset + 4);
            int header = 8;
            if (size == 1) {
                size = moov.getLong(offset + 8);
                header = 16;
            } else if (size == 0) {
                size = end - offset;
            }
            if (size < header || offset + size > end) return false;
            int payload = offset + header;
            if (CONTAINERS.contains(name(type))) {
                if (!shiftChunkOffsets(moov, payload, (int) (offset + size), from, delta)) return false;
            } else if (type == type("stco")) {
                int count = moov.getInt(payload + 4);
                for (int i = 0; i < count; i++) {
                    int entry = payload + 8 + 4 * i;
                    long value = moov.getInt(entry) & 0xFFFFFFFFL;
                    if (value < from) continue;
                    value += delta;
                    if (value > 0xFFFFFFFFL) return false;
                    moov.putInt(entry, (int) value);
                }
            } else if (type == type("co64")) {
                int count = moov.getInt(payload + 4);
                for (int i = 0; i < count; i++) {
                    int entry = payload + 8 + 8 * i;
                    long value = moov.getLong(entry);
                    if (value >= from) moov.putLong(entry, value + delta);
                }
            }
            offset += (int) size;
        }
        return true;
    }

    private static int type(String name) {
        return (name.charAt(0) << 24) | (name.charAt(1) << 16) | (name.charAt(2) << 8) | name.charAt(3);
    }

    private static String name(int type) {
        return new String(new char[] { (char) (type >>> 24 & 0xFF), (char) (type >>> 16 & 0xFF), (char) (type >>> 8 & 0xFF), (char) (type & 0xFF) });
    }
}
