import type { PluginListenerHandle } from '@capacitor/core';

export interface AudioFromVideoRetrieverPlugin {
  extractAudio(options: { path: string, outputPath: string, includeData?: boolean }): Promise<{ path: string, dataUrl?: string }>;

  addListener(
    eventName: 'compressProgress',
    listenerFunc: (event: { progress: number }) => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;
}
