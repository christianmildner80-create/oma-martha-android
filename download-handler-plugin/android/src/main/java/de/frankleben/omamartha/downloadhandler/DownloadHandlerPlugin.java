package de.frankleben.omamartha.downloadhandler;

import android.content.Intent;
import android.net.Uri;
import android.webkit.DownloadListener;
import android.webkit.WebView;
import androidx.core.content.FileProvider;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Reicht Datei-Downloads aus der eingebetteten WebView an den System-Browser weiter (Christian,
 * 26.08.2026: der Link "App-Update installieren" unter IT-Solution zeigte innerhalb der App nur
 * den rohen Binaerinhalt der .apk als Text an - die WebView selbst kann Downloads nicht
 * entgegennehmen, ein manueller Umweg ueber Chrome hat als Workaround funktioniert). load() laeuft
 * beim Start automatisch und setzt den DownloadListener auf der Bridge-WebView, fuer jeden
 * Download-Link in der App, nicht nur die APK-Update-Seite - bleibt als Fallback fuer normale
 * Datei-Downloads (z.B. Etiketten/PDF) unveraendert bestehen.
 *
 * installApk() (24.09.2026, Christian: "kann man das Update auch so loesen ohne einen direkten
 * Download zu machen") - laedt die APK-Datei DIREKT in der App (nicht per Chrome-Umweg) und
 * oeffnet danach sofort den System-Installationsdialog. Android verlangt bei jeder Installation
 * ausserhalb des Play Store weiterhin eine manuelle Nutzerbestaetigung (Sicherheitsvorgabe des
 * Betriebssystems, laesst sich ohne Geraeteverwaltung/MDM nicht umgehen) - dieser eine Tap bleibt
 * also bestehen, aber der Umweg ueber Chrome+Downloads-Ordner faellt komplett weg.
 */
@CapacitorPlugin(name = "DownloadHandler")
public class DownloadHandlerPlugin extends Plugin {

    @Override
    public void load() {
        WebView webView = getBridge().getWebView();
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
        });
    }

    @PluginMethod
    public void installApk(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("Keine URL angegeben.");
            return;
        }
        // Netzwerk-I/O NICHT auf dem Haupt-Thread (Android wirft sonst NetworkOnMainThreadException) -
        // eigener Hintergrund-Thread reicht fuer diese einmalige, kleine Aktion, kein eigenes
        // Executor-Setup noetig.
        new Thread(() -> {
            try {
                HttpURLConnection verbindung = (HttpURLConnection) new URL(url).openConnection();
                verbindung.connect();
                if (verbindung.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    call.reject("Download fehlgeschlagen (HTTP " + verbindung.getResponseCode() + ").");
                    return;
                }
                // Fester Dateiname im App-Cache (durch file_paths.xml/cache-path bereits fuer den
                // FileProvider freigegeben, keine weitere Konfiguration noetig) - ueberschreibt
                // eine evtl. vorherige, unvollstaendig heruntergeladene Version automatisch.
                File zielDatei = new File(getContext().getCacheDir(), "oma-martha-update.apk");
                try (InputStream eingabe = verbindung.getInputStream(); FileOutputStream ausgabe = new FileOutputStream(zielDatei)) {
                    byte[] puffer = new byte[8192];
                    int gelesen;
                    while ((gelesen = eingabe.read(puffer)) != -1) {
                        ausgabe.write(puffer, 0, gelesen);
                    }
                }
                Uri apkUri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", zielDatei);
                Intent installIntent = new Intent(Intent.ACTION_VIEW);
                installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(installIntent);
                call.resolve();
            } catch (Exception e) {
                call.reject("Download/Installation fehlgeschlagen: " + e.getMessage());
            }
        }).start();
    }
}
