<div align="right">

**English** | [简体中文](README.zh-CN.md)

</div>

# CarLyrics for Spotify

A lightweight synchronized-lyrics overlay for Spotify on Android and OPPO Car+.

CarLyrics only reads Spotify's media metadata, playback state, and position. It does not create, take over, or control a media session, so it will not pause or resume Spotify. If notification access is temporarily unavailable, it can continue reading playback state through Spotify's MediaSession.

The app detects the `OplusMirage` virtual display created by OPPO Car+. While Car+ is connected, the lyrics overlay is placed on the car display; after disconnection, it falls back to the phone. Once initial permissions have been granted, you can launch CarLyrics directly from the Car+ app list without opening it again on the phone.

Android 8.0 or later is required. The phone-only overlay may work on other Android devices that provide notification access, overlay permission, and Spotify media notifications. Car-display support has only been verified with an OPPO phone and Car+; other phones, vehicles, and projection systems are untested and are not guaranteed to work. Official CarPlay and Android Auto interfaces, as well as other closed projection systems, are generally unsupported.

![CarLyrics running in OPPO Car+](docs/carplus-preview.jpg)

## Features

- No Root required
- Reads the current Spotify track, playback state, and position without controlling playback
- Automatically falls back to Spotify's MediaSession when the notification listener is unavailable
- Compares NetEase Cloud Music and QQ Music results and prefers the source with more complete line-by-line translations; LRCLIB is used as a fallback
- Supports Simplified and Traditional Chinese switching in real time (Traditional by default)
- Displays a Chinese translation below the current foreign-language lyric when the selected source provides one
- Uses a fixed-size, draggable lyrics card designed for the space below the Car+ music card
- Detects the Car+ virtual display automatically, prioritizes the car display while connected, and returns to the phone after disconnection
- Follows the Android light/dark theme automatically, with manual theme selection available
- Includes a scrollable settings screen and privacy-conscious diagnostic logs

## Installation and Usage

1. Download and install the APK from [Releases](https://github.com/Phinsin666/CarLyricsForSpotify/releases).
2. On first use, grant **Notification access** and **Display over other apps** permission.
3. Connect the phone to Car+, enter the Car+ interface, and start playing a track in Spotify.
4. Open **Spotify Floating Lyrics** directly from the Car+ app list and tap **Start floating lyrics**.
5. Return to the map and music-card screen. The lyrics card should appear on the car display and can be dragged into position.

After the initial permission setup, normal use does not require opening CarLyrics on the phone. Launching it from Car+ is sufficient. Starting it on the phone also works when the Car+ virtual display already exists: the single overlay window will migrate to and prioritize the car display.

Use the **Chinese lyrics** setting to switch between Simplified and Traditional Chinese. Existing lyrics update immediately; no new search or service restart is required.

For foreign-language songs, only the current line includes its Chinese translation so the overlay keeps a stable size. Previous and next lines remain in the original language. If neither lyrics source provides a translation, only the original lyrics are shown.

If the app remains on “Waiting for Spotify” or loses its connection, use **Reconnect Spotify**. If the issue persists, copy the diagnostic log and include it in a GitHub Issue. Logs do not include Spotify account data, track titles, artist names, lyric text, or complete playback history.

An internet connection is required to retrieve lyrics, and the first lookup for a track may take a moment.

> Read [Important Notes](NOTICE.md) before use. Install, authorize, configure, and troubleshoot the app only while safely parked. Do not operate a phone or debug the app while driving.

## Permissions

- **Notification access** — reads Spotify's media notification metadata, playback state, and position.
- **Display over other apps** — displays the lyrics card over Car+ or other Android apps.
- **Network access** — retrieves synchronized lyrics from supported providers.
- **Foreground service / notifications** — keeps the lyrics service running in the background.
- **Battery-optimization exemption (optional)** — improves background reliability on systems with aggressive process management.

CarLyrics does not request Root access, ask for Spotify credentials, or upload listening history.

## Build from Source

Requirements: JDK 17 and Android SDK 35.

```bash
./gradlew assembleDebug
```

The APK is generated under `app/build/outputs/apk/debug/`.

## Known Limitations

- Android 8.0 or later is required. Some vendor background restrictions may still stop the service.
- Car-display support has only been verified with an OPPO phone and Car+. The app includes specific handling for Car+'s `OplusMirage` virtual display.
- A working phone overlay does not guarantee support on another car display. The projection system must expose an Android display on which the app can create an overlay window.
- The application ID is `cmccwm.mobilemusic` for Car+ music-app recognition. Installation may conflict with another app using the same package name.
- NetEase Cloud Music and QQ Music lyric endpoints used by this project are not official public APIs and may change or stop working. LRCLIB is used as a fallback.
- Lyric matching depends on the track title, artist, and duration. Live versions, remixes, and tracks with identical names may be missing or mismatched.
- Chinese translations are only available when a lyrics provider supplies synchronized translations. LRCLIB commonly provides original lyrics only.

## Lyrics and Copyright

This project provides lyric lookup and display functionality and does not distribute a lyrics database. Lyrics remain the property of their respective authors and rights holders. Users are responsible for complying with local laws and the applicable service terms.

Spotify, QQ Music, NetEase Cloud Music, OPPO, Car+, and their names, icons, and trademarks belong to their respective owners. This is an independent, unofficial community project and is not affiliated with, authorized by, or endorsed by those companies.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=Phinsin666/CarLyricsForSpotify&type=Date)](https://star-history.com/#Phinsin666/CarLyricsForSpotify&Date)

## Support the Project

CarLyrics is free and open source. If it helps you, consider starring the repository. Donations are entirely optional and do not constitute a purchase, subscription, or service commitment.

<table>
  <tr>
    <th>Alipay</th>
    <th>WeChat Pay</th>
  </tr>
  <tr>
    <td><img src="docs/donate-alipay.jpg" alt="Alipay donation QR code" width="360"></td>
    <td><img src="docs/donate-wechat.jpg" alt="WeChat Pay donation QR code" width="360"></td>
  </tr>
</table>

## License

Released under the [MIT License](LICENSE).
