import type { PluginListenerHandle } from '@capacitor/core';

export interface AudioFromVideoRetrieverPlugin {
  extractAudio(options: { path: string, outputPath?: string, includeData?: boolean }): Promise<{ path: string, dataUrl?: string, fileSize: number, mimeType: string }>;
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
 * Share videos (one still frame over a generation's audio): H.264 + AAC-LC in an MP4 with the moov
 * atom first. Also the audio alone at another speed, as AAC-LC in an .m4a (neither platform has an
 * MP3 encoder). AVFoundation on iOS, Media3 Transformer on Android; not registered on the web.
 */
export interface StillVideoPlugin {
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
