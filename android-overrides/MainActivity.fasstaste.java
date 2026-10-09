package de.frankleben.omamartha;

import android.view.KeyEvent;
import android.webkit.WebView;
import android.widget.Toast;
import com.getcapacitor.BridgeActivity;

/**
 * Freie TC52-Tasten (Christian, 09.10.2026): Keycode 104 oeffnet die Seite "Fass anschliessen",
 * Keycode 103 die Seite "Abschreibung" der Web-App. ACHTUNG: Keycode 10036 ist die SCAN-Taste und darf nie belegt werden.
 *
 * Tastenerkennung (Christian, 09.10.2026: "schalte die Tastenerkennung nochmal an"): Ist in der Web-App der
 * Debug-Modus an (localStorage "debug_modus" = "1", Umschalter 🐞 in der Fusszeile), zeigt jeder Tastendruck
 * kurz "Taste erkannt: Keycode X" als Toast. Ohne Debug-Modus bleibt die App ruhig. Alle anderen Tasten
 * werden unveraendert durchgereicht.
 */
public class MainActivity extends BridgeActivity {
    private static final int FASS_TASTE_KEYCODE = 104;
    private static final int ABSCHREIBUNG_TASTE_KEYCODE = 103;

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
        if (event.getKeyCode() == FASS_TASTE_KEYCODE || event.getKeyCode() == ABSCHREIBUNG_TASTE_KEYCODE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && web != null) {
                String ziel = event.getKeyCode() == FASS_TASTE_KEYCODE ? "modules/inventur/fass_anschluss.php" : "modules/abschreibung/index.php";
                web.evaluateJavascript("window.location.href = '/imbiss/" + ziel + "';", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
