package de.frankleben.omamartha;

import android.view.KeyEvent;
import android.widget.Toast;
import com.getcapacitor.BridgeActivity;

/**
 * DIAGNOSE-Version (24.09.2026, TEMPORAER) - zeigt bei jedem Tastendruck kurz den Android-
 * Keycode als Toast an, um die bisher unbelegte Taste oberhalb des Scan-Triggers am TC52 zu
 * identifizieren, bevor sie fest auf den Debug-Modus-Toggle gelegt wird (siehe
 * imbiss_tc52_freie_taste_debug_modus.md). Greift NICHT in die Verarbeitung ein - immer
 * super.dispatchKeyEvent() mit dessen echtem Ergebnis, damit der normale Scan-Trigger,
 * Zurueck-Taste usw. unveraendert weiter funktionieren.
 */
public class MainActivity extends BridgeActivity {
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Toast.makeText(this, "Taste erkannt: Keycode " + event.getKeyCode(), Toast.LENGTH_SHORT).show();
        }
        return super.dispatchKeyEvent(event);
    }
}
