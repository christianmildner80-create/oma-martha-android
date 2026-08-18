# Oma Martha – Android-App (APK) bauen

Diese App ist nur eine **Huelle**: sie zeigt beim Start immer die Live-Seite
`https://oma-martha-frankleben.de/imbiss/` in einem eingebetteten Browser (Capacitor/WebView)
an – wie eine feste Verknuepfung, aber als echte, eigenstaendige App ohne Adressleiste,
installierbar auch ohne Chrome/Play Store auf dem Zebra TC75x. Aenderungen an der Webseite
(neue Module, Bugfixes) erscheinen automatisch, ohne dass die APK neu gebaut werden muss –
nur bei Aenderungen am App-Icon/Namen/dieser Konfiguration ist ein Neubau noetig.

**Wichtiger Vorbehalt:** Diese App nutzt weiterhin die "Android System WebView"-Komponente
des Geraets zum Rendern. Ist die auf dem TC75x sehr alt, kann es trotzdem zu Darstellungs-
problemen kommen. Vor dem Bauen lohnt sich ein kurzer Blick auf dem Geraet unter
Einstellungen → Apps → Android System WebView → Versionsnummer.

Enthaelt außerdem das Plugin `@capacitor/local-notifications` fuer die tägliche
17:30-Kuechenschluss-Erinnerung (Christian, 19.08.2026) - die eigentliche Planung/Abbrechung
der Erinnerung passiert im Code der Webseite selbst (`assets/js/kuechenschluss_erinnerung.js`),
nicht hier in der App-Huelle.

Außerdem `@capacitor/filesystem` + `@capacitor/share` (Christian, 19.08.2026: Web-Share-API/
Blob-Download fuer Beweisfotos funktionieren im WebView der nativen App nicht zuverlaessig -
"das läuft über die apk" war die Ursache) - genutzt in
`modules/beweisfotos/index.php` fuer echtes Herunterladen/Teilen innerhalb der App.

## Weg 1: Kostenlos ueber GitHub Actions bauen lassen (empfohlen, kein eigener PC noetig)

Dieses Verzeichnis enthaelt `.github/workflows/build-apk.yml` - baut die APK automatisch bei
jedem Push zu GitHub, komplett kostenlos (GitHub Actions hat ein grosszuegiges Gratis-Kontingent
fuer private wie oeffentliche Repos).

1. Ordner (inkl. der versteckten `.github`-Unterordner!) in ein neues, leeres GitHub-Repository
   pushen.
2. Auf github.com im Repo oben auf den Reiter **"Actions"** gehen - der Lauf "Android-APK bauen"
   startet automatisch.
3. Nach ein paar Minuten (grün ✓ = fertig) im selben Lauf ganz unten bei **"Artifacts"** auf
   "oma-martha-app-debug" klicken - laedt eine ZIP mit der fertigen `app-debug.apk` herunter.
4. Weiter bei "APK auf den TC75x bringen" unten.

## Weg 2: Muss auf einem separaten PC gebaut werden

Dieser Server hat kein Java/Node.js/Android SDK installiert (reiner PHP-Webserver) – das
Bauen einer APK gehoert dort auch nicht hin. Bitte diesen Ordner auf einen PC/Laptop mit
folgender Software kopieren:

- **Node.js** (LTS, z.B. 18 oder 20) – https://nodejs.org
- **Android Studio** (bringt Java/JDK mit) – https://developer.android.com/studio
  - Beim ersten Start im SDK Manager zusaetzlich installieren:
    - Android SDK Platform 23 (Marshmallow, unser Ziel-Minimum)
    - Eine aktuelle Platform (z.B. 34) als Build-/Target-SDK

## Schritt fuer Schritt

```bash
# 1. In diesen Ordner wechseln, Abhaengigkeiten installieren
npm install

# 2. Android-Projekt generieren (legt den Ordner android/ an)
npx cap add android

# 3. Minimale Android-Version pruefen/setzen
#    Datei android/variables.gradle oeffnen, sicherstellen:
#      minSdkVersion = 23
#    (23 = Android 6.0 Marshmallow - das ist unser Ziel fuer den TC75x)

# 4. App-Icon setzen (optional, sonst Capacitor-Standardicon):
#    assets/icon.png liegt hier schon bei (aus dem imbiss-Projekt uebernommen, 512x512 -
#    fuers Grobe ausreichend, fuer ein scharfes Icon idealerweise durch eine 1024x1024-Version
#    ersetzen), dann:
npx @capacitor/assets generate --android

# 5. Konfiguration synchronisieren
npx cap sync android

# 6. In Android Studio oeffnen
npx cap open android
```

In Android Studio dann oben im Menue: **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
Nach dem Bauen liegt die Datei hier:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

Das ist eine **Debug-APK** – reicht zum Testen auf dem TC75x völlig aus (keine Google-Signatur
noetig). Fuer den dauerhaften Einsatz auf mehreren Geraeten spaeter ggf. einen signierten
Release-Build erstellen (Android Studio fragt danach, wenn man "Generate Signed Bundle / APK"
statt "Build APK(s)" waehlt).

## APK auf den TC75x bringen

1. `app-debug.apk` per USB-Kabel auf den TC75x kopieren (oder per E-Mail/Cloud-Ordner, falls
   vorhanden) – z.B. in den "Download"-Ordner.
2. Auf dem TC75x mit einem Dateimanager die APK antippen.
3. Falls gefragt: "Installation aus unbekannten Quellen erlauben" bestaetigen (einmalig,
   Android fragt das bei Apps, die nicht aus dem Play Store kommen).
4. Installieren, fertig – Icon "Oma Martha" erscheint auf dem Startbildschirm.
