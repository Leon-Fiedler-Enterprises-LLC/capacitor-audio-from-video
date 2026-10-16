import type { PluginListenerHandle } from '@capacitor/core';

export interface ExtractAudioOptions {
  /** The video or audio file, as the file picker returns it. */
  path: string;
  /** Where to write the audio. Defaults to a new file in the cache directory. */
  outputPath?: string;
  /** Also returns the audio as a data URL. */
  includeData?: boolean;
}

export interface ExtractAudioResult {
  path: string;
  dataUrl?: string;
  fileSize: number;
  mimeType: string;
}

export interface CreateStillVideoOptions {
  /** The audio: an https URL (downloaded natively, alongside the images), a file:// URI or an absolute path. */
  audioUrl: string;
  /** The still frame: an https, data:, file:// or Capacitor.convertFileSrc URL. Cover-fitted. */
  imageUrl: string;
  /** Watermark artwork authored at 9:16, drawn at full height and centered. Same URL forms as imageUrl. */
  overlayUrl?: string;
  /** Even. */
  width: number;
  /** Even. */
  height: number;
  /** Selects the higher-quality frame encode. */
  hd: boolean;
  /** Tempo change that keeps the pitch. Defaults to 1. */
  speedFactor?: number;
  /** Where to write the .mp4: a file:// URI or an absolute path. Replaced if it exists. */
  outputPath: string;
}

export interface ChangeAudioSpeedOptions {
  /** The audio: an https URL (downloaded natively), a file:// URI or an absolute path. */
  audioUrl: string;
  /** Tempo change that keeps the pitch, like ffmpeg's atempo. */
  speedFactor: number;
  /** Where to write the .m4a: a file:// URI or an absolute path. Replaced if it exists. */
  outputPath: string;
}

/**
 * The app's media processing on iOS (AVFoundation) and Android (MediaCodec, Media3 Transformer);
 * not registered on the web.
 *
 * - extractAudio: the audio of a video or audio file, cut and shrunk for upload.
 * - Share videos (one still frame over a generation's audio): H.264 + AAC-LC in an MP4 with the
 *   moov atom first.
 * - The audio alone at another speed, as AAC-LC in an .m4a (neither platform has an MP3 encoder).
 */
export interface MediaPlugin {
  extractAudio(options: ExtractAudioOptions): Promise<ExtractAudioResult>;
  /**
   * Starts downloading the inputs of a likely createStillVideo() (and the encoder) ahead of time,
   * e.g. when a share sheet opens. Replaces the previous call's downloads.
   */
  prepare(options: { audioUrl?: string, imageUrl?: string, overlayUrl?: string }): Promise<void>;
  createStillVideo(options: CreateStillVideoOptions): Promise<{ path: string, duration: number }>;
  changeAudioSpeed(options: ChangeAudioSpeedOptions): Promise<{ path: string, duration: number }>;
  /** 0...1 while createStillVideo() encodes the audio. */
  addListener(eventName: 'progress', listenerFunc: (event: { progress: number }) => void): Promise<PluginListenerHandle>;
}

/**
 * @deprecated The plugin's name before it became Media, with only extractAudio. Binaries built
 * before the rename register only this one; newer binaries keep it for older web bundles.
 */
export type AudioFromVideoRetrieverPlugin = Pick<MediaPlugin, 'extractAudio'>;
