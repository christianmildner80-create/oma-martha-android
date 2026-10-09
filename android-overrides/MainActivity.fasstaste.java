package de.frankleben.omamartha;

import android.view.KeyEvent;
import android.webkit.WebView;
import android.widget.Toast;
import com.getcapacitor.BridgeActivity;

/**
 * Freie TC52-Taste (oberhalb des Scan-Triggers, Keycode 104): oeffnet die Seite "Fass anschliessen" der
 * Web-App (Christian, 09.10.2026). ACHTUNG: Keycode 10036 ist die SCAN-Taste und darf nie belegt werden.
 *
 * Tastenerkennung (Christian, 09.10.2026: "schalte die Tastenerkennung nochmal an"): Ist in der Web-App der
 * Debug-Modus an (localStorage "debug_modus" = "1", Umschalter 🐞 in der Fusszeile), zeigt jeder Tastendruck
 * kurz "Taste erkannt: Keycode X" als Toast. Ohne Debug-Modus bleibt die App ruhig. Alle anderen Tasten
 * werden unveraendert durchgereicht.
 */
public class MainActivity extends BridgeActivity {
    private static final int FASS_TASTE_KEYCODE = 104;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && web != null) {
            final int code = event.getKeyCode();
            web.evaluateJavascript(
                "(function(){try{return localStorage.getItem('debug_modus')==='1';}catch(e){return false;}})()",
                value -> {
                    if ("true".equals(value)) {
                        Toast.makeText(MainActivity.this, "Taste erkannt: Keycode " + code, Toast.LENGTH_SHORT).show();
                    }
                });
        }
        if (event.getKeyCode() == FASS_TASTE_KEYCODE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && web != null) {
                web.evaluateJavascript("window.location.href = '/imbiss/modules/inventur/fass_anschluss.php';", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
