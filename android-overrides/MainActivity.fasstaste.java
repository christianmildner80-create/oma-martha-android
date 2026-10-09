package de.frankleben.omamartha;

import android.view.KeyEvent;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

/**
 * Freie TC52-Tasten (Christian, 09.10.2026): Keycode 104 oeffnet die Seite "Fass anschliessen",
 * Keycode 103 die Seite "Abschreibung" der Web-App. ACHTUNG: Keycode 10036 ist die SCAN-Taste und darf nie
 * belegt werden. Alle anderen Tasten (Scan-Trigger, Zurueck, Lautstaerke ...) werden unveraendert
 * durchgereicht. Die Diagnose-Anzeige der Tastencodes (Toast) ist entfernt.
 */
public class MainActivity extends BridgeActivity {
    private static final int FASS_TASTE_KEYCODE = 104;
    private static final int ABSCHREIBUNG_TASTE_KEYCODE = 103;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == FASS_TASTE_KEYCODE || code == ABSCHREIBUNG_TASTE_KEYCODE) {
            WebView web = getBridge() != null ? getBridge().getWebView() : null;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && web != null) {
                String ziel = code == FASS_TASTE_KEYCODE ? "modules/inventur/fass_anschluss.php" : "modules/abschreibung/index.php";
                web.evaluateJavascript("window.location.href = '/imbiss/" + ziel + "';", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
