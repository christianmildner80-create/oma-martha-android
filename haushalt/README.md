# Haushalt – Android-App (APK)

Zweite App im selben Repo (Christian, 06.11.2026: „wir wollten das gleich mit einer Repo machen“).
Huelle wie die Imbiss-App: zeigt immer die Live-Seite `https://oma-martha-frankleben.de/haushalt/`
(nur aus dem Heimnetz erreichbar). Aenderungen an der Webseite erscheinen sofort, Neubau nur bei
Aenderungen an Icon/Name/Plugins.

- Eigene App-ID `de.frankleben.haushalt` → laeuft NEBEN der Imbiss-App, eigener Speicher (eigene Anmeldung per QR).
- Enthaelt das Niimbot-B21S-Plugin (`../niimbot-print-plugin`) fuer direkten Etikettendruck und
  `@capacitor/app` fuer die Zurueck-Taste.
- Gebaut von `.github/workflows/build-haushalt-apk.yml` bei Push auf `haushalt/**`;
  die APK liegt danach unter dem festen Link
  `https://oma-martha-frankleben.de/imbiss/modules/it_soloution/download_apk.php?app=haushalt`.
