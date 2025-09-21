package com.fiedlertech.capacitor.audio.from.video;

import android.Manifest;
import android.content.ContentResolver;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.IOException;

@CapacitorPlugin(name = "AudioFromVideoRetriever", permissions = {
    @Permission(
            alias = "storage-old",
            strings = {
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }
    )
})
public class AudioFromVideoRetrieverPlugin extends Plugin {

    private AudioFromVideoRetriever implementation = new AudioFromVideoRetriever();

    public String getStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return "";
        } else {
            return "storage-old";
        }
    }

    @PermissionCallback
    private void videoPermsCallback(PluginCall call) {
        if (getPermissionState(getStoragePermission()) == PermissionState.GRANTED) {
            extractAudio(call);
        } else {
            call.reject("Permission is required to take a picture");
        }
    }

    @PluginMethod
    public void extractAudio(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && getPermissionState(getStoragePermission()) != PermissionState.GRANTED) {
            requestPermissionForAlias(getStoragePermission(), call, "videoPermsCallback");
            return;
        }
        String path = call.getString("path");
        String outputPath = call.getString("outputPath");
        Boolean includeData = call.getBoolean("includeData", false);

        ContentResolver resolver = bridge.getContext().getContentResolver();
        File inputFile = implementation.getFileObject(path, resolver);
        File outputFile = (outputPath == null || outputPath.isEmpty())
                ? new File(bridge.getContext().getCacheDir(), "afv_tmp_" + System.currentTimeMillis() + ".tmp")
                : implementation.getFileObject(outputPath, resolver);

        implementation.extractAudio(inputFile, outputFile, new AudioFromVideoRetriever.ExtractionCallback() {
            @Override
            public void onExtractionCompleted(File audioFile, String mimeType) throws IOException {
				// If the extension of outputPath doesn't match mimeType, rename file to correct extension
				String desiredExt = mimeType != null && mimeType.equals("audio/mpeg") ? ".mp3" : ".m4a";
				String finalPath = outputPath;
				try {
					String abs = audioFile.getAbsolutePath();
					int lastDot = abs.lastIndexOf('.')
							;
					String candidate;
					if (lastDot > 0) {
						candidate = abs.substring(0, lastDot) + desiredExt;
					} else {
						candidate = abs + desiredExt;
					}
					if (!abs.endsWith(desiredExt)) {
						File renamed = new File(candidate);
						if (audioFile.renameTo(renamed)) {
							finalPath = renamed.getAbsolutePath();
							audioFile = renamed;
						}
					}
				} catch (Exception ignored) {}

				JSObject ret = new JSObject();
				if (includeData) {
					ret.put("dataUrl", implementation.getDataUrlFromAudioFile(audioFile, mimeType));
				}
				ret.put("path", finalPath);
				ret.put("mimeType", mimeType);
				ret.put("fileSize", audioFile.length());
				call.resolve(ret);
            }

            @Override
            public void onExtractionFailed(String errorMessage) {
                call.reject(errorMessage);
            }

            @Override
            public void onExtractionProgress(Double progress) {
                
            }
        });
    }
}
