import { WebPlugin } from '@capacitor/core';

import type { AudioFromVideoRetrieverPlugin, ExtractAudioOptions, ExtractAudioResult } from './definitions';

export class AudioFromVideoRetrieverWeb
  extends WebPlugin
  implements AudioFromVideoRetrieverPlugin {
  async extractAudio(_options: ExtractAudioOptions): Promise<ExtractAudioResult> {
    throw this.unimplemented('Not implemented on web.');
  }
}
