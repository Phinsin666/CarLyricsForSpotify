<div align="right">

**English** | [简体中文](NOTICE.zh-CN.md)

</div>

# Important Notes

Please read the following before using, modifying, or distributing this project.

## Driving Safety

- Install the app, grant permissions, position the overlay, and troubleshoot only while safely parked.
- Do not operate a phone or look at lyrics for extended periods while driving. Users are responsible for following local traffic laws.
- This project does not guarantee suitability for every vehicle, head unit, projection system, or driving scenario.

## Compatibility

- Android 8.0 or later is required, together with notification access, overlay permission, and network access. Vendor background restrictions may still interrupt the service.
- Car-display behavior has only been verified with an OPPO phone and Car+. The app includes specific support for Car+'s `OplusMirage` virtual display. Other phones, Android devices, vehicles, and projection systems are untested and are not guaranteed to work.
- While Car+ is connected, the app prioritizes the car's virtual display for its single overlay window. It returns to the phone after disconnection. Other systems can only work if they expose an Android display on which the app may create an overlay. Official CarPlay and Android Auto interfaces and other closed systems are generally unsupported.
- The application ID is `cmccwm.mobilemusic` for compatibility with Car+ music-app recognition. It may conflict with another installed app using the same package name.
- Android, Spotify, Car+, or vendor system updates may affect compatibility.

## Permissions and Privacy

- Notification access is used only to read Spotify track metadata, playback state, and position. Android grants this as system-wide notification access, so install the app only from this repository or from a build you created yourself.
- If the notification listener is unavailable, the app may read Spotify's current state through Android MediaSession. It does not create, take over, or control a media session.
- Overlay permission is used to display lyrics above Car+ or other apps.
- The app does not request Spotify credentials, include account login, or upload listening history.
- You can revoke notification access and overlay permission in Android Settings when you stop using the app.

## Lyrics Services and Copyright

- The app compares NetEase Cloud Music and QQ Music lyric and translation coverage, with LRCLIB as a fallback. The first two integrations use unofficial endpoints that may change, become rate-limited, or stop working.
- Lyrics can be missing, delayed, or matched to a different version of a track and should not be relied on for professional or commercial purposes.
- Chinese translation is shown only when a provider supplies synchronized line-by-line translations; not every song has one.
- This project does not distribute a lyrics database. Lyrics and related content remain the property of their respective authors and rights holders.
- Users are responsible for complying with local laws and the terms of Spotify, QQ Music, NetEase Cloud Music, LRCLIB, and other services.

## Diagnostic Logs

- The app can generate local diagnostic logs for notification-listener, MediaSession, overlay-service, and lyric-request troubleshooting.
- Logs do not contain Spotify account details, track titles, artist names, lyric text, or complete playback history. Users decide whether to copy and submit them.

## Unofficial Project

Spotify, QQ Music, NetEase Cloud Music, OPPO, Car+, and their names, icons, and trademarks belong to their respective owners. This is an independent, unofficial community project and is not affiliated with, authorized by, endorsed by, or partnered with any of them.

The software is provided “as is” under the MIT License, without a promise of continued availability, complete accuracy, or fitness for a particular purpose.
