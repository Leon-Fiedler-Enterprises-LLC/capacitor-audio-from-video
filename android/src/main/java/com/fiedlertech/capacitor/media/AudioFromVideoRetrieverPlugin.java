package com.fiedlertech.capacitor.media;

import android.Manifest;

import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

/**
 * The plugin's name before it became Media, with only extractAudio. Older web bundles (Live Updates)
 * call it on newer binaries. Remove once no supported web bundle uses it.
 */
@CapacitorPlugin(name = "AudioFromVideoRetriever", permissions = {
    @Permission(alias = "storage-old", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
})
public class AudioFromVideoRetrieverPlugin extends AudioExtractionPlugin {}
