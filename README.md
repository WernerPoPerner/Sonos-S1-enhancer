# S1 Player – teilen und auf Sonos S1 abspielen

Mit dieser Android-App kannst du Radio und Podcasts aus anderen Apps auf einem **Sonos-S1-System**
abspielen, zum Beispiel aus Deutschlandfunk, ARD Audiothek, Apple Podcasts oder dem Browser.

1. In der anderen App auf **Teilen** tippen.
2. **Auf Sonos abspielen** wählen.
3. Den Raum auswählen und auf **Abspielen** tippen. Sonos holt sich den Stream danach selbst; das
   Handy muss nicht in der Nähe bleiben.

## Was die App kann

| Geteilter Inhalt | Ergebnis |
| --- | --- |
| Link auf eine Deutschlandfunk-Seite mit Audio (Sendung, Interview, Podcast-Folge) | die MP3 der Sendung, dazu als Alternative das Live-Programm |
| Link auf deutschlandfunk.de / deutschlandfunkkultur.de / deutschlandfunknova.de ohne Audio | das Live-Programm des Senders |
| Text wie „Ich höre Deutschlandfunk Kultur“ ohne Link | das Live-Programm |
| Apple-Podcasts-Link (Folge oder Sendung) | die Folge bzw. die neuesten Folgen |
| RSS-Feed eines Podcasts | die neuesten Folgen |
| Direkter Stream- oder MP3-Link, `.m3u`, `.pls` | wird direkt abgespielt |
| Andere Webseiten mit eingebettetem Audio (`og:audio`, `<audio>`, verlinkte MP3s) | das gefundene Audio |
| Spotify, Apple Music, YouTube, Amazon Music, Deezer, Tidal | **nicht möglich**, weil kopiergeschützt. Diese Dienste direkt in der Sonos-S1-App einrichten. |

Werden mehrere Audios gefunden, fragt die App, welches gespielt werden soll.

In der App selbst gibt es außerdem:

- die Raumauswahl mit allen Sonos-Gruppen; Befehle gehen automatisch an den Gruppenkoordinator
- Wiedergabe, Pause, Stopp und Lautstärke (für die ganze Gruppe)
- Feste Sender: Deutschlandfunk, Kultur, Nova, Dokumente & Debatten
- Eine Verlaufsliste der zuletzt gespielten Inhalte
- Ein Feld zum Einfügen eines Links
- Einstellungen:
  - **IP-Adresse eines Lautsprechers**: nötig, falls der Router die automatische Suche (SSDP/Multicast) blockiert.
    Die IP steht in der Sonos-App unter *Einstellungen → System → Über mein System*.
  - **Beim Teilen sofort abspielen**: spielt ohne Nachfrage im zuletzt genutzten Raum.
  - **HTTPS als HTTP senden**: hilft sehr alten S1-Geräten, die verschlüsselte Streams nicht abspielen.

## Installation

Die APK baut GitHub Actions bei jedem Push:

1. Im Repository auf **Actions → Build APK** gehen und den neuesten erfolgreichen Lauf öffnen.
2. Unter *Artifacts* **S1-Player-apk** herunterladen und entpacken.
3. `app-release.apk` auf das Handy kopieren und öffnen. Dafür musst du einmal „Installation aus
   unbekannten Quellen“ erlauben.

Voraussetzungen: Android 8 oder neuer. Das Handy muss im selben WLAN sein wie das Sonos-System.

> Die APK ist mit einem Debug-Schlüssel signiert, den CI bei jedem Lauf neu erzeugt. Lässt sich ein
> Update nicht installieren, deinstalliere die alte Version vorher.

Selbst bauen (Android SDK nötig): `./gradlew :app:assembleRelease`

## Aufbau

- `core/`: reines Kotlin/JVM ohne Android-Abhängigkeiten, mit Unit-Tests (`./gradlew :core:test`)
  - `StreamResolver`: macht aus geteiltem Text oder Link abspielbare Audio-URLs
  - `SonosDiscovery`: SSDP-Suche und Raum-/Gruppenstruktur (`GetZoneGroupState`, S1 und S2)
  - `SonosClient`: UPnP/SOAP-Steuerung (`SetAVTransportURI`, Play/Pause/Stop, Lautstärke)
- `app/`: Android-App mit Jetpack Compose
  - `ShareActivity`: das Teilen-Ziel; erscheint als halbtransparentes Fenster über der anderen App
  - `MainActivity`: Fernbedienung, Sender, Verlauf, Einstellungen

Live-Streams über `http://` schickt die App als `x-rincon-mp3radio://` an Sonos. So behandelt Sonos
sie als Radiosender und verbindet sich bei Abbrüchen neu. Podcast-Folgen gehen als normale Tracks raus.
