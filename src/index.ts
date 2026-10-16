import { registerPlugin } from '@capacitor/core';

import type { AudioFromVideoRetrieverPlugin, MediaPlugin } from './definitions';

// No web implementation: Capacitor.isPluginAvailable('Media') is the capability check.
const Media = registerPlugin<MediaPlugin>('Media');

/** @deprecated Use Media. Only for binaries built before the rename, which lack it. */
const AudioFromVideoRetriever = registerPlugin<AudioFromVideoRetrieverPlugin>(
  'AudioFromVideoRetriever',
  {
    web: () => import('./web').then(m => new m.AudioFromVideoRetrieverWeb()),
  },
);

export * from './definitions';
export { AudioFromVideoRetriever, Media };
