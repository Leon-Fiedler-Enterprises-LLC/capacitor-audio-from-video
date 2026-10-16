# @fiedlertech/capacitor-media

Native media processing for Capacitor apps (iOS: AVFoundation, Android: MediaCodec and Media3):

- `extractAudio`: the audio of a video or audio file, cut and shrunk for upload
- `createStillVideo`: a share video, one still frame over an audio file
- `changeAudioSpeed`: audio at another speed with the pitch kept

The plugin registers as `Media`. Until every supported binary has `Media`, it also registers its old
name, `AudioFromVideoRetriever` (only `extractAudio`), so web bundles from before the rename keep
working through Live Updates.

## Install

```bash
npm install @fiedlertech/capacitor-media
npx cap sync
```

## API

<docgen-index>

* [`extractAudio(...)`](#extractaudio)
* [`prepare(...)`](#prepare)
* [`createStillVideo(...)`](#createstillvideo)
* [`changeAudioSpeed(...)`](#changeaudiospeed)
* [`addListener('progress', ...)`](#addlistenerprogress)
* [Interfaces](#interfaces)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

The app's media processing on iOS (AVFoundation) and Android (MediaCodec, Media3 Transformer);
not registered on the web.

- extractAudio: the audio of a video or audio file, cut and shrunk for upload.
- Share videos (one still frame over a generation's audio): H.264 + AAC-LC in an MP4 with the
  moov atom first.
- The audio alone at another speed, as AAC-LC in an .m4a (neither platform has an MP3 encoder).

### extractAudio(...)

```typescript
extractAudio(options: ExtractAudioOptions) => Promise<ExtractAudioResult>
```

| Param         | Type                                                                |
| ------------- | ------------------------------------------------------------------- |
| **`options`** | <code><a href="#extractaudiooptions">ExtractAudioOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#extractaudioresult">ExtractAudioResult</a>&gt;</code>

--------------------


### prepare(...)

```typescript
prepare(options: { audioUrl?: string; imageUrl?: string; overlayUrl?: string; }) => Promise<void>
```

Starts downloading the inputs of a likely createStillVideo() (and the encoder) ahead of time,
e.g. when a share sheet opens. Replaces the previous call's downloads.

| Param         | Type                                                                        |
| ------------- | --------------------------------------------------------------------------- |
| **`options`** | <code>{ audioUrl?: string; imageUrl?: string; overlayUrl?: string; }</code> |

--------------------


### createStillVideo(...)

```typescript
createStillVideo(options: CreateStillVideoOptions) => Promise<{ path: string; duration: number; }>
```

| Param         | Type                                                                        |
| ------------- | --------------------------------------------------------------------------- |
| **`options`** | <code><a href="#createstillvideooptions">CreateStillVideoOptions</a></code> |

**Returns:** <code>Promise&lt;{ path: string; duration: number; }&gt;</code>

--------------------


### changeAudioSpeed(...)

```typescript
changeAudioSpeed(options: ChangeAudioSpeedOptions) => Promise<{ path: string; duration: number; }>
```

| Param         | Type                                                                        |
| ------------- | --------------------------------------------------------------------------- |
| **`options`** | <code><a href="#changeaudiospeedoptions">ChangeAudioSpeedOptions</a></code> |

**Returns:** <code>Promise&lt;{ path: string; duration: number; }&gt;</code>

--------------------


### addListener('progress', ...)

```typescript
addListener(eventName: 'progress', listenerFunc: (event: { progress: number; }) => void) => Promise<PluginListenerHandle>
```

0...1 while createStillVideo() encodes the audio.

| Param              | Type                                                   |
| ------------------ | ------------------------------------------------------ |
| **`eventName`**    | <code>'progress'</code>                                |
| **`listenerFunc`** | <code>(event: { progress: number; }) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### Interfaces


#### ExtractAudioResult

| Prop           | Type                |
| -------------- | ------------------- |
| **`path`**     | <code>string</code> |
| **`dataUrl`**  | <code>string</code> |
| **`fileSize`** | <code>number</code> |
| **`mimeType`** | <code>string</code> |


#### ExtractAudioOptions

| Prop              | Type                 | Description                                                              |
| ----------------- | -------------------- | ------------------------------------------------------------------------ |
| **`path`**        | <code>string</code>  | The video or audio file, as the file picker returns it.                  |
| **`outputPath`**  | <code>string</code>  | Where to write the audio. Defaults to a new file in the cache directory. |
| **`includeData`** | <code>boolean</code> | Also returns the audio as a data URL.                                    |


#### CreateStillVideoOptions

| Prop              | Type                 | Description                                                                                             |
| ----------------- | -------------------- | ------------------------------------------------------------------------------------------------------- |
| **`audioUrl`**    | <code>string</code>  | The audio: an https URL (downloaded natively, alongside the images), a file:// URI or an absolute path. |
| **`imageUrl`**    | <code>string</code>  | The still frame: an https, data:, file:// or Capacitor.convertFileSrc URL. Cover-fitted.                |
| **`overlayUrl`**  | <code>string</code>  | Watermark artwork authored at 9:16, drawn at full height and centered. Same URL forms as imageUrl.      |
| **`width`**       | <code>number</code>  | Even.                                                                                                   |
| **`height`**      | <code>number</code>  | Even.                                                                                                   |
| **`hd`**          | <code>boolean</code> | Selects the higher-quality frame encode.                                                                |
| **`speedFactor`** | <code>number</code>  | Tempo change that keeps the pitch. Defaults to 1.                                                       |
| **`outputPath`**  | <code>string</code>  | Where to write the .mp4: a file:// URI or an absolute path. Replaced if it exists.                      |


#### ChangeAudioSpeedOptions

| Prop              | Type                | Description                                                                        |
| ----------------- | ------------------- | ---------------------------------------------------------------------------------- |
| **`audioUrl`**    | <code>string</code> | The audio: an https URL (downloaded natively), a file:// URI or an absolute path.  |
| **`speedFactor`** | <code>number</code> | Tempo change that keeps the pitch, like ffmpeg's atempo.                           |
| **`outputPath`**  | <code>string</code> | Where to write the .m4a: a file:// URI or an absolute path. Replaced if it exists. |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |

</docgen-api>
