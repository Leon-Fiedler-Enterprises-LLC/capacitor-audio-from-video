import { registerPlugin } from '@capacitor/core';

import type { AudioFromVideoRetrieverPlugin, StillVideoPlugin } from './definitions';

const AudioFromVideoRetriever = registerPlugin<AudioFromVideoRetrieverPlugin>(
  'AudioFromVideoRetriever',
  {
    web: () => import('./web').then(m => new m.AudioFromVideoRetrieverWeb()),
  },
);

// No web implementation: Capacitor.isPluginAvailable('StillVideo') is the capability check.
const StillVideo = registerPlugin<StillVideoPlugin>('StillVideo');

export * from './definitions';
export { AudioFromVideoRetriever, StillVideo };
