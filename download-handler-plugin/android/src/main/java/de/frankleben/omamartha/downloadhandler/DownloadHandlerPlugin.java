package de.frankleben.omamartha.downloadhandler;

import android.content.Intent;
import android.net.Uri;
import android.webkit.DownloadListener;
import android.webkit.WebView;

import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Reicht Datei-Downloads aus der eingebetteten WebView an den System-Browser weiter (Christian,
 * 26.08.2026: der Link "App-Update installieren" unter IT-Solution zeigte innerhalb der App nur
 * den rohen Binaerinhalt der .apk als Text an - die WebView selbst kann Downloads nicht
 * entgegennehmen, ein manueller Umweg ueber Chrome hat als Workaround funktioniert). Ohne
 * eigene JS-Methoden - der Zweck ist rein, dass load() beim Start automatisch laeuft und den
 * DownloadListener auf der Bridge-WebView setzt, fuer jeden Download-Link in der App, nicht nur
 * die APK-Update-Seite.
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
}
