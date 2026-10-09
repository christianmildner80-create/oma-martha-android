package de.frankleben.omamartha;

import android.view.KeyEvent;
import com.getcapacitor.BridgeActivity;

/**
 * Fest verdrahtete freie Taste (TC52, oberhalb des Scan-Triggers, Keycode 104): oeffnet die Seite
 * "Fass anschliessen" der Web-App (Christian, 09.10.2026: statt Debug-Modus lieber das Fass anschliessen).
 * Ersetzt die DIAGNOSE-Version vom 24.09.2026, die bei JEDER Taste einen Toast mit dem Keycode
 * zeigte. ACHTUNG: Keycode 10036 ist die SCAN-Taste (Christian, 09.10.2026) und darf nie belegt
 * werden. Alle anderen Tasten (Scan-Trigger, Zurueck, Lautstaerke ...) werden unveraendert
 * durchgereicht.
 */
public class MainActivity extends BridgeActivity {
    private static final int FASS_TASTE_KEYCODE = 104;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == FASS_TASTE_KEYCODE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().evaluateJavascript("window.location.href = '/imbiss/modules/inventur/fass_anschluss.php';", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
