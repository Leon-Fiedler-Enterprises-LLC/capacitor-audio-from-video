package com.fiedlertech.capacitor.media;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessingPipeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.ToInt16PcmAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * An audio file's audio as AAC-LC, at the source's sample rate (8-48 kHz) and channel count (up
 * to stereo), optionally at another tempo with the pitch kept (Sonic, like ffmpeg's atempo).
 *
 * <p>Runs MediaCodec directly, decoder into encoder, as AVAssetReader / AVAssetWriter do on iOS.
 * Media3 Transformer drains its audio graph on a 10 ms poll, which holds an audio-only export to
 * about 20x realtime on any device: slower than ffmpeg.wasm for a long message.
 */
@OptIn(markerClass = UnstableApi.class)
final class AacTranscoder {

    interface Output {
        /** The encoder's format, with its AudioSpecificConfig (csd-0). Comes before any sample. */
        void onFormat(MediaFormat format) throws IOException;

        /** An AAC frame; {@code sample} is only valid during the call. */
        void onSample(ByteBuffer sample, long timeUs) throws IOException;
    }

    /** Decoders that decode every MP3 frame in an input buffer, not only the first. */
    private static final java.util.List<String> MULTI_FRAME_MP3_DECODERS = java.util.Arrays.asList("c2.android.mp3.decoder", "OMX.google.mp3.decoder");
    private static final int PACKED_INPUT_SIZE = 32 * 1024;
    private static final java.util.List<String> GOOGLE_AAC_ENCODERS = java.util.Arrays.asList("c2.android.aac.encoder", "OMX.google.aac.encoder");
    private static final int MIN_SAMPLE_RATE = 8_000;
    private static final int MAX_SAMPLE_RATE = 48_000;
    private static final int[] AAC_SAMPLE_RATES = { 8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000 };

    private AacTranscoder() {}

    /** Transcodes {@code source} into {@code output} and returns the duration written, in microseconds. */
    static long transcode(File source, double speedFactor, int bitRate, Output output, @Nullable StillVideoEncoder.ProgressListener progress) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        AudioProcessingPipeline pipeline = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            MediaFormat inputFormat = extractor.getTrackFormat(StillVideoEncoder.selectTrack(extractor, "audio/"));
            long sourceDurationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION) ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IOException("The audio file has no audio track");

            decoder = createDecoder(mime);
            // Google's MP3 decoders decode every frame in an input buffer. An MP3 frame is 26 ms
            // of audio and each buffer is a round trip to the media process (about 1 ms on the
            // emulator), so a buffer of them decodes many times faster than a frame at a time.
            boolean packFrames = MediaFormat.MIMETYPE_AUDIO_MPEG.equals(mime) && MULTI_FRAME_MP3_DECODERS.contains(decoder.getName());
            if (packFrames) inputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PACKED_INPUT_SIZE);
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();
            ByteBuffer sample = ByteBuffer.allocateDirect(Math.max(PACKED_INPUT_SIZE, inputFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? inputFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0));
            sample.limit(0);
            boolean sampleRead = false;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ArrayDeque<ByteBuffer> pcm = new ArrayDeque<>();
            int frameBytes = 0;
            int outputSampleRate = 0;
            boolean extractorDone = false;
            boolean decoderDone = false;
            boolean pcmDone = false;
            boolean encoderInputDone = false;
            int encoderInputIndex = -1;
            long framesQueued = 0;
            boolean wroteSample = false;
            long primingUs = 0;
            int lastPercent = -1;

            boolean encoderDone = false;
            // Each stage takes every buffer its codec has ready: the codecs run in the media
            // process, and moving one buffer per round trip would leave them idle.
            while (!encoderDone) {
                boolean progressed = false;

                while (!extractorDone) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index < 0) break;
                    ByteBuffer buffer = decoder.getInputBuffer(index);
                    if (buffer == null) throw new IOException("The audio decoder has no input buffer");
                    buffer.clear();
                    long timeUs = -1;
                    boolean ended = false;
                    // One sample, or as many whole MP3 frames as fit.
                    while (true) {
                        if (!sampleRead) {
                            sample.clear();
                            int size = extractor.readSampleData(sample, 0);
                            if (size < 0) {
                                ended = true;
                                break;
                            }
                            sample.limit(size);
                            sampleRead = true;
                        }
                        if (sample.remaining() > buffer.remaining()) {
                            if (buffer.position() == 0) throw new IOException("An audio frame is larger than the decoder's input buffer");
                            break;
                        }
                        if (timeUs < 0) timeUs = extractor.getSampleTime();
                        buffer.put(sample);
                        sampleRead = false;
                        extractor.advance();
                        if (!packFrames) break;
                    }
                    if (buffer.position() > 0) {
                        decoder.queueInputBuffer(index, 0, buffer.position(), timeUs, 0);
                        if (progress != null && sourceDurationUs > 0) {
                            int percent = (int) Math.min(99, 100 * timeUs / sourceDurationUs);
                            if (percent > lastPercent) {
                                lastPercent = percent;
                                progress.onProgress(percent / 100.0);
                            }
                        }
                    } else if (ended) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        extractorDone = true;
                    }
                    progressed = true;
                }

                while (!decoderDone) {
                    int index = decoder.dequeueOutputBuffer(info, 0);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    progressed = true;
                    if (index < 0) continue;
                    if (encoder == null) {
                        // The PCM the decoder writes, and what the encoder takes after the processors.
                        MediaFormat decoded = decoder.getOutputFormat();
                        int sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        int encoding = decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) ? decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) : C.ENCODING_PCM_16BIT;
                        pipeline = pipeline(new AudioProcessor.AudioFormat(sampleRate, channels, encoding), speedFactor);
                        AudioProcessor.AudioFormat encoded = pipeline.isOperational() ? pipeline.getOutputAudioFormat() : new AudioProcessor.AudioFormat(sampleRate, channels, encoding);
                        if (encoded.encoding != C.ENCODING_PCM_16BIT) throw new IOException("Unsupported PCM encoding " + encoded.encoding);
                        outputSampleRate = encoded.sampleRate;
                        frameBytes = 2 * encoded.channelCount;
                        encoder = createEncoder(encoded.sampleRate, encoded.channelCount, bitRate);
                    }
                    ByteBuffer buffer = decoder.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset).limit(info.offset + info.size);
                        buffer = buffer.slice().order(ByteOrder.nativeOrder());
                        if (pipeline.isOperational()) {
                            while (buffer.hasRemaining()) {
                                pipeline.queueInput(buffer);
                                drain(pipeline, pcm);
                            }
                        } else {
                            pcm.add(copy(buffer));
                        }
                    }
                    decoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderDone = true;
                        if (pipeline != null && pipeline.isOperational()) pipeline.queueEndOfStream();
                    }
                }

                if (decoderDone && !pcmDone) {
                    if (pipeline != null && pipeline.isOperational()) {
                        drain(pipeline, pcm);
                        pcmDone = pipeline.isEnded();
                    } else {
                        pcmDone = true;
                    }
                }
                if (pcmDone && encoder == null) throw new IOException("The audio file has no audio");

                while (encoder != null && !encoderInputDone && (!pcm.isEmpty() || pcmDone)) {
                    if (encoderInputIndex < 0) encoderInputIndex = encoder.dequeueInputBuffer(0);
                    if (encoderInputIndex < 0) break;
                    ByteBuffer buffer = encoder.getInputBuffer(encoderInputIndex);
                    if (buffer == null) throw new IOException("The audio encoder has no input buffer");
                    buffer.clear();
                    // Whole frames only, so the timestamps stay exact.
                    int capacity = buffer.remaining() - buffer.remaining() % frameBytes;
                    while (!pcm.isEmpty() && buffer.position() < capacity) {
                        ByteBuffer next = pcm.peek();
                        int take = Math.min(next.remaining(), capacity - buffer.position());
                        ByteBuffer part = next.duplicate();
                        part.limit(part.position() + take);
                        buffer.put(part);
                        next.position(next.position() + take);
                        if (!next.hasRemaining()) pcm.poll();
                    }
                    int size = buffer.position();
                    boolean last = pcmDone && pcm.isEmpty();
                    encoder.queueInputBuffer(encoderInputIndex, 0, size, framesQueued * C.MICROS_PER_SECOND / outputSampleRate, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    encoderInputDone = last;
                    framesQueued += size / frameBytes;
                    encoderInputIndex = -1;
                    progressed = true;
                }

                while (encoder != null) {
                    int index = encoder.dequeueOutputBuffer(info, 0);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    progressed = true;
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat encoded = encoder.getOutputFormat();
                        primingUs = primingFrames(encoder, encoded) * C.MICROS_PER_SECOND / outputSampleRate;
                        output.onFormat(encoded);
                    } else if (index >= 0) {
                        ByteBuffer buffer = encoder.getOutputBuffer(index);
                        boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        if (buffer != null && info.size > 0 && !config) {
                            buffer.position(info.offset).limit(info.offset + info.size);
                            // The priming frames come out before 0, where the muxer's edit list skips them.
                            output.onSample(buffer, info.presentationTimeUs - primingUs);
                            wroteSample = true;
                        }
                        encoder.releaseOutputBuffer(index, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                            break;
                        }
                    }
                }

                if (!progressed) SystemClock.sleep(1);
            }
            if (!wroteSample) throw new IOException("The audio encoder wrote nothing");
            return framesQueued * C.MICROS_PER_SECOND / outputSampleRate;
        } finally {
            extractor.release();
            if (decoder != null) release(decoder);
            if (encoder != null) release(encoder);
            if (pipeline != null) pipeline.reset();
        }
    }

    /**
     * 16-bit PCM, downmixed to stereo from more channels, and Sonic for the tempo change (and a
     * resample where the rate is outside AAC's). Not operational when the PCM needs none of it.
     */
    private static AudioProcessingPipeline pipeline(AudioProcessor.AudioFormat input, double speedFactor) throws IOException {
        ImmutableList.Builder<AudioProcessor> processors = ImmutableList.builder();
        processors.add(new ToInt16PcmAudioProcessor());
        if (input.channelCount > 2) {
            ChannelMixingAudioProcessor mixer = new ChannelMixingAudioProcessor();
            mixer.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(input.channelCount, 2));
            processors.add(mixer);
        }
        SonicAudioProcessor sonic = new SonicAudioProcessor();
        if (speedFactor > 0 && Math.abs(speedFactor - 1) > 0.001) {
            sonic.setSpeed((float) speedFactor);
            sonic.setPitch(1f);
        }
        int sampleRate = Math.min(Math.max(input.sampleRate, MIN_SAMPLE_RATE), MAX_SAMPLE_RATE);
        if (!isEncoderSampleRate(sampleRate)) sampleRate = nearestAacSampleRate(sampleRate);
        if (sampleRate != input.sampleRate) sonic.setOutputSampleRateHz(sampleRate);
        processors.add(sonic);

        AudioProcessingPipeline pipeline = new AudioProcessingPipeline(processors.build());
        try {
            pipeline.configure(input);
        } catch (AudioProcessor.UnhandledAudioFormatException e) {
            throw new IOException("Unsupported audio format: " + input, e);
        }
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT);
        return pipeline;
    }

    private static void drain(AudioProcessingPipeline pipeline, ArrayDeque<ByteBuffer> pcm) {
        while (true) {
            ByteBuffer output = pipeline.getOutput();
            if (!output.hasRemaining()) return;
            pcm.add(copy(output));
        }
    }

    private static ByteBuffer copy(ByteBuffer buffer) {
        ByteBuffer copy = ByteBuffer.allocate(buffer.remaining());
        copy.put(buffer).flip();
        return copy;
    }

    /**
     * The silence the encoder puts ahead of the audio: its own figure where it states one, else
     * Google's AAC encoder's (FDK, 2048 frames for AAC-LC). Left in for other encoders, where it
     * only delays the audio by tens of milliseconds.
     */
    private static long primingFrames(MediaCodec encoder, MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) return format.getInteger(MediaFormat.KEY_ENCODER_DELAY);
        return GOOGLE_AAC_ENCODERS.contains(encoder.getName()) ? 2048 : 0;
    }

    /** Google's MP3 decoder where there is one (see MULTI_FRAME_MP3_DECODERS), else the default. */
    private static MediaCodec createDecoder(String mime) throws IOException {
        if (MediaFormat.MIMETYPE_AUDIO_MPEG.equals(mime)) {
            for (String name : MULTI_FRAME_MP3_DECODERS) {
                try {
                    return MediaCodec.createByCodecName(name);
                } catch (IOException | IllegalArgumentException ignored) {
                    // Not on this device.
                }
            }
        }
        return MediaCodec.createDecoderByType(mime);
    }

    private static MediaCodec createEncoder(int sampleRate, int channels, int bitRate) throws IOException {
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, supportedBitRate(encoder, bitRate));
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            return encoder;
        } catch (RuntimeException e) {
            encoder.release();
            throw new IOException("Couldn't start the audio encoder: " + e.getMessage(), e);
        }
    }

    /** The bitrate, within the encoder's range. */
    private static int supportedBitRate(MediaCodec encoder, int bitRate) {
        try {
            MediaCodecInfo.AudioCapabilities audio = encoder.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).getAudioCapabilities();
            if (audio != null) return audio.getBitrateRange().clamp(bitRate);
        } catch (RuntimeException ignored) {
        }
        return bitRate;
    }

    private static boolean isEncoderSampleRate(int sampleRate) {
        for (int rate : AAC_SAMPLE_RATES) if (rate == sampleRate) return true;
        return false;
    }

    private static int nearestAacSampleRate(int sampleRate) {
        int nearest = AAC_SAMPLE_RATES[0];
        for (int rate : AAC_SAMPLE_RATES) if (Math.abs(rate - sampleRate) < Math.abs(nearest - sampleRate)) nearest = rate;
        return nearest;
    }

    private static void release(MediaCodec codec) {
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
        }
        codec.release();
    }
}
