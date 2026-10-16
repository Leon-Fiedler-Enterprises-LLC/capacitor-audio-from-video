import { registerPlugin } from '@capacitor/core';

import type { MediaPlugin } from './definitions';

// No web implementation: Capacitor.isPluginAvailable('Media') is the capability check.
const Media = registerPlugin<MediaPlugin>('Media');

export * from './definitions';
export { Media };
