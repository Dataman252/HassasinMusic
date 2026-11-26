<p align="center"><img src="docs/logo.svg" width="140" alt="Hassasin Music logo"></p>

<h1 align="center">Hassasin Music</h1>

<p align="center">An Android music player for people who care about sound: bit-perfect USB DAC output, a full effects suite, automatic covers and lyrics, and three looks.</p>

## Download

Get the latest APK from [Releases](../../releases). Android 9 or newer.

## Features

**Output** (pick one on the Output tab)
- **Rich tuned** (default): the phone's own decoder and sound tuning.
- **Hi-Res**: 24-bit files go to Android as 32-bit float instead of being cut to 16-bit.
- **Phone DSP**: offload, where the phone's audio chip decodes the file (saves battery).
- **USB Direct**: bit-perfect to a USB DAC.
- The quality details show which decoder runs, what Android receives, any resampling and the output device.

**Sound**
- The phone's volume keys control the DAC's own volume in USB Direct.
- Calls work with the DAC plugged in: the player hands the DAC back to Android during a call and takes it back afterwards.
- 10-band EQ with presets and parametric filters, bass styles (sub rumble, 808, punchy and more), and tone controls.
- Earphone corrections from the AutoEq database.
- Effects: gain control, convolver (load your own impulse responses), bass, clarity, spectrum extension, tube, AnalogX, dynamic system, surround, reverb, crossfeed, compressor, master gain and limiter.
- Tuning sliders: temperature, bass extension, bass texture, vocal, sibilance, presence, air and soundstage.

**Library**
- Tabs: All songs, Recently played, Most played, Hi-Res, Albums, Artists, Folders, Recently added and Top rated.
- Choose which music folders to play from.
- Plays FLAC, ALAC, WAV, MP3, AAC and Opus, plus APE, DSD and more through a bundled FFmpeg decoder.

**Covers and lyrics**
- Missing or wrong covers are found automatically (MusicBrainz / Cover Art Archive, Apple's catalogue), with sure matches only.
- Synced lyrics from LRCLIB are fetched ahead of time for the whole library. A Hinglish option shows Hindi lyrics in Roman letters.
- Cover sharpness slider.

**Look**
- Three looks: **Glow** (light from each cover), **Soft** (matte, raised controls) and **Flow** (full-screen cover that melts into its colour).
- A live visualizer that also works over USB Direct, and 7 seek-bar styles behind the play controls.
- Swipe the cover to change song.
- Lock-screen player in your chosen look. Notification controls show the cover and include shuffle and repeat.

## Updates and support

The app checks this repository's releases and offers each new version in-app; Android asks before installing it. If you enjoy the app, there's an optional UPI coffee link in Settings.

## Build

Needs Android Studio (for its JDK) and the Android SDK with NDK.

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## Credits

- Lyrics: [LRCLIB](https://lrclib.net).
- Covers: [MusicBrainz](https://musicbrainz.org), the [Cover Art Archive](https://coverartarchive.org) and Apple's iTunes Search API.
- Earphone corrections: [AutoEq](https://github.com/jaakkopasanen/AutoEq).
- Reverb: Freeverb by Jezar at Dreampoint.
- Filters: Robert Bristow-Johnson's Audio EQ Cookbook.
- Built with AndroidX Media3, the Jellyfin FFmpeg decoder, Jetpack Compose, Kotlin and OkHttp.
- Fonts: Unbounded, Manrope and JetBrains Mono (SIL OFL).

Poweramp, ViPER4Android, HiBy MSEB, Flowie and NYX inspired some of the features. All code and design here are original, and product names belong to their owners.

## Licence

GPL-3.0: see [LICENSE](LICENSE). The app bundles the Jellyfin FFmpeg decoder, which is GPL-3.0.
