import { WebPlugin } from '@capacitor/core';

import type { AudioFromVideoRetrieverPlugin } from './definitions';

export class AudioFromVideoRetrieverWeb
  extends WebPlugin
  implements AudioFromVideoRetrieverPlugin {
  async extractAudio(options: { path: string, outputPath?: string, includeData?: boolean | undefined }): Promise<{ path: string, dataUrl?: string, fileSize: number, mimeType: string }> {
    throw this.unimplemented('Not implemented on web.');
  }
}
