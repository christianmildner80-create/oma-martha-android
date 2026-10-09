package de.frankleben.omamartha;

import android.view.KeyEvent;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

/**
 * Freie TC52-Taste (Christian, 09.10.2026): Keycode 104 oeffnet die Seite "Fass anschliessen" der Web-App.
 * ACHTUNG: Keycode 10036 ist die SCAN-Taste, Keycode 103 loest ebenfalls den Scanner aus (Christian,
 * 09.10.2026) - beide duerfen nie belegt werden. Alle anderen Tasten (Scan-Trigger, Zurueck,
 * Lautstaerke ...) werden unveraendert durchgereicht.
 */
public class MainActivity extends BridgeActivity {
    private static final int FASS_TASTE_KEYCODE = 104;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == FASS_TASTE_KEYCODE) {
            WebView web = getBridge() != null ? getBridge().getWebView() : null;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && web != null) {
                web.evaluateJavascript("window.location.href = '/imbiss/modules/inventur/fass_anschluss.php';", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
