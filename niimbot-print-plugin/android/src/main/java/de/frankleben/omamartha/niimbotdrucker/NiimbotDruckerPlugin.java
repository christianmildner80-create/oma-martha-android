package de.frankleben.omamartha.niimbotdrucker;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.UUID;

/**
 * Natives Bluetooth-LE-Plugin fuer den Niimbot B21S Etikettendrucker (26.08.2026, Christian:
 * "der Bluetoothdrucker nimbot bs21s ist nun am TC angeschlossen"). Bewusst SCHLANK gehalten -
 * nur Verbinden/Senden/Trennen als generische BLE-Grundoperationen. Das eigentliche
 * Niimbot-Paket-Protokoll (Checksummen, Bild-Kodierung, Druck-Befehlsreihenfolge) liegt
 * ABSICHTLICH in JavaScript (modules/haccp/etikett.php), NICHT hier - Protokoll-Details sind
 * naturgemaess fehleranfaellig und muessen ohne Warten auf einen APK-Neubau/Christians Download
 * korrigierbar sein (die Live-Seite laedt ja direkt vom Server, ganz ohne App-Update). Nur diese
 * generische BLE-Huelle braucht einen echten Android-Build.
 *
 * BLE-Protokoll-Eckdaten (siehe Recherche 26.08.2026, Quelle u.a. niimbluelib/niimprint):
 * Service e7810a71-73ae-499d-8c15-faa9aef0c3f2, Characteristic bef8d6c9-9c21-4c9e-b632-bd58c1009f9f
 * (dient sowohl zum Schreiben als auch fuer Notify-Antworten).
 */
// 30.08.2026, nach "die Seite schließt sich" auf einem Samsung S23+ (aktuelles Android): auf
// Android 12+ braucht Bluetooth-Scan/-Verbindung die NEUEN Laufzeit-Berechtigungen BLUETOOTH_SCAN
// und BLUETOOTH_CONNECT (nicht mehr nur ACCESS_FINE_LOCATION wie auf dem alten TC75x/Android 6) -
// standen zwar schon im Manifest, wurden aber nirgends im Projekt tatsächlich per Dialog
// angefragt. Ohne Zusage wirft z.B. scanner.startScan() eine SecurityException, die die App zum
// Abstürzen bringt. Jetzt über Capacitors eingebauten Berechtigungs-Mechanismus angefragt.
@CapacitorPlugin(
    name = "NiimbotDrucker",
    permissions = {
        @Permission(strings = { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT }, alias = "bluetooth")
    }
)
public class NiimbotDruckerPlugin extends Plugin {

    private static final UUID SERVICE_UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2");
    private static final UUID CHAR_UUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long VERBINDEN_TIMEOUT_MS = 20000;
    // Absicherungs-Timeout, falls onCharacteristicWrite() nie feuert (manche BLE-Stacks liefern
    // das bei WRITE_TYPE_NO_RESPONSE nicht zuverlaessig) - dann wird trotzdem nach dieser Zeit
    // weitergemacht, statt fuer immer zu haengen.
    private static final long SCHREIB_TIMEOUT_MS = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic zielCharakteristik;
    private BluetoothLeScanner scanner;
    private ScanCallback aktiverScanCallback;
    private PluginCall verbindenAufruf;
    private PluginCall sendenAufruf;
    private final Runnable sendenTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            beendeSendenErfolgreich();
        }
    };

    @PluginMethod
    public void verbinden(PluginCall call) {
        // Ab Android 12 (API 31) muessen BLUETOOTH_SCAN/BLUETOOTH_CONNECT per echtem Dialog
        // angefragt werden, sonst wirft der Scan weiter unten eine SecurityException. Auf
        // aelteren Geraeten (TC75x, Android 6) existieren diese Laufzeit-Berechtigungen gar
        // nicht als Konzept - dort reicht die alte ACCESS_FINE_LOCATION-Pruefung.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (getPermissionState("bluetooth") != com.getcapacitor.PermissionState.GRANTED) {
                requestPermissionForAlias("bluetooth", call, "nachBerechtigungVerbinden");
                return;
            }
        } else if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            call.reject("Standort-Berechtigung fehlt - wird für Bluetooth-Suche benötigt, bitte in den Android-App-Einstellungen erlauben");
            return;
        }
        verbindenNachBerechtigung(call);
    }

    @PermissionCallback
    private void nachBerechtigungVerbinden(PluginCall call) {
        if (getPermissionState("bluetooth") != com.getcapacitor.PermissionState.GRANTED) {
            call.reject("Bluetooth-Berechtigung wurde nicht erteilt - ohne sie kann nicht nach dem Drucker gesucht werden.");
            return;
        }
        verbindenNachBerechtigung(call);
    }

    private void verbindenNachBerechtigung(PluginCall call) {
        trenneAlteVerbindung();

        BluetoothManager bluetoothManager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            call.reject("Bluetooth ist aus oder auf diesem Gerät nicht verfügbar - bitte einschalten");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            call.reject("Bluetooth-LE-Scan auf diesem Gerät nicht verfügbar");
            return;
        }

        String gesuchterName = call.getString("geraeteName", "B21");
        verbindenAufruf = call;
        call.setKeepAlive(true);

        aktiverScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice geraet = result.getDevice();
                String name = geraet.getName();
                if (name != null && name.contains(gesuchterName)) {
                    scanner.stopScan(this);
                    aktiverScanCallback = null;
                    verbindeMitGeraet(geraet);
                }
            }
        };
        scanner.startScan(aktiverScanCallback);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (aktiverScanCallback != null) {
                    scanner.stopScan(aktiverScanCallback);
                    aktiverScanCallback = null;
                    beendeVerbindenMitFehler("Kein Niimbot-Drucker gefunden (Name enthält \"" + gesuchterName + "\") - ist er eingeschaltet und in Reichweite?");
                }
            }
        }, SCAN_TIMEOUT_MS);
    }

    private void verbindeMitGeraet(BluetoothDevice geraet) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (verbindenAufruf != null) {
                    beendeVerbindenMitFehler("Zeitüberschreitung beim Verbinden mit dem Drucker.");
                }
            }
        }, VERBINDEN_TIMEOUT_MS);

        gatt = geraet.connectGatt(getContext(), false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // KEINE MTU-Aushandlung (26.08.2026 zurueckgenommen): requestMtu() fuehrte auf dem
                    // TC75x zu sofortigem "Verbindung abgebrochen, Status 133" - der alte Bluetooth-
                    // Stack dieses Geraets kommt damit nicht klar. Die eigentliche Ursache (Bildzeilen-
                    // Pakete werden bei Standard-MTU sonst stillschweigend abgeschnitten) wird
                    // stattdessen in JS geloest: sendeBefehl() in etikett.php zerlegt jedes Paket
                    // jetzt in kleine, sicher unter der Standard-MTU liegende Haeppchen.
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    JSObject daten = new JSObject();
                    notifyListeners("getrennt", daten);
                    if (verbindenAufruf != null) {
                        beendeVerbindenMitFehler("Verbindung zum Drucker abgebrochen (Status " + status + ").");
                    }
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    beendeVerbindenMitFehler("Dienste des Druckers konnten nicht gelesen werden (Status " + status + ").");
                    return;
                }
                BluetoothGattService dienst = g.getService(SERVICE_UUID);
                if (dienst == null) {
                    beendeVerbindenMitFehler("Erwarteter Bluetooth-Dienst am Drucker nicht gefunden - falsches Gerät oder anderes Modell?");
                    return;
                }
                // 30.08.2026, nach "kommt trotzdem nur ein leeres Etikett" trotz nachweislich
                // erfolgreicher Notify-Aktivierung (siehe onDescriptorWrite-Fix): die feste
                // CHAR_UUID war eine Annahme aus fruehrer Recherche. Die echte Referenz
                // niimbluelib legt sich NICHT auf eine feste UUID fest, sondern sucht dynamisch
                // die Characteristic im Service, die sowohl NOTIFY als auch WRITE_NO_RESPONSE
                // unterstuetzt - genau das jetzt hier nachgebaut, mit Fallback auf die alte feste
                // UUID falls die Suche nichts Passendes findet (z.B. anderes Druckermodell).
                BluetoothGattCharacteristic charakteristik = null;
                for (BluetoothGattCharacteristic kandidat : dienst.getCharacteristics()) {
                    int eigenschaften = kandidat.getProperties();
                    boolean kannNotify = (eigenschaften & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                    boolean kannSchreiben = (eigenschaften & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                    if (kannNotify && kannSchreiben) {
                        charakteristik = kandidat;
                        break;
                    }
                }
                if (charakteristik == null) {
                    charakteristik = dienst.getCharacteristic(CHAR_UUID);
                }
                if (charakteristik == null) {
                    beendeVerbindenMitFehler("Keine passende Bluetooth-Characteristic (Notify+Write) am Drucker gefunden - falsches Gerät oder anderes Modell?");
                    return;
                }
                JSObject gefundenDaten = new JSObject();
                gefundenDaten.put("characteristicUuid", charakteristik.getUuid().toString());
                notifyListeners("characteristicGefunden", gefundenDaten);
                zielCharakteristik = charakteristik;
                g.setCharacteristicNotification(charakteristik, true);
                BluetoothGattDescriptor cccd = charakteristik.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                } else {
                    beendeVerbindenErfolgreich();
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
                // 30.08.2026: status wurde bisher NICHT geprueft - die App meldete "verbunden",
                // sobald ueberhaupt eine onDescriptorWrite-Antwort kam, egal ob das Aktivieren der
                // Benachrichtigungen wirklich erfolgreich war. Beim ersten echten Testdruck kamen
                // ueber den gesamten Druckvorgang (mehrere Testdrucke, >20s Polling) NULL Notify-
                // Antworten vom Drucker an - moeglich, dass dieser Schreibvorgang auf diesem
                // Geraet/Drucker im Hintergrund fehlschlaegt und bisher unbemerkt blieb. Jetzt wird
                // bei einem Fehlschlag klar ein Fehler gemeldet statt stillschweigend "verbunden".
                if (CCCD_UUID.equals(descriptor.getUuid())) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        beendeVerbindenErfolgreich();
                    } else {
                        beendeVerbindenMitFehler("Benachrichtigungen konnten am Drucker nicht aktiviert werden (Status " + status + ") - Antworten vom Drucker wären nicht empfangbar gewesen.");
                    }
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
                JSObject daten = new JSObject();
                daten.put("bytesBase64", Base64.encodeToString(characteristic.getValue(), Base64.NO_WRAP));
                notifyListeners("antwort", daten);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
                // Echtes Fertig-Signal vom BLE-Stack statt nur einer festen Pause (26.08.2026:
                // "Bluetooth-Stack hat den Schreibvorgang abgelehnt" trat bei vielen tausend
                // Schreibvorgaengen pro Etikett wiederholt auf - vermutlich weil die feste Pause
                // manchmal nicht ausreichte, bevor der naechste Schreibvorgang gestartet wurde).
                beendeSendenErfolgreich();
            }
        });
    }

    private void beendeVerbindenErfolgreich() {
        if (verbindenAufruf == null) return;
        PluginCall aufruf = verbindenAufruf;
        verbindenAufruf = null;
        JSObject ergebnis = new JSObject();
        ergebnis.put("verbunden", true);
        aufruf.resolve(ergebnis);
    }

    private void beendeVerbindenMitFehler(String fehlertext) {
        if (verbindenAufruf == null) return;
        PluginCall aufruf = verbindenAufruf;
        verbindenAufruf = null;
        aufruf.reject(fehlertext);
        trenneAlteVerbindung();
    }

    @PluginMethod
    public void senden(PluginCall call) {
        String bytesBase64 = call.getString("bytesBase64");
        if (bytesBase64 == null || gatt == null || zielCharakteristik == null) {
            call.reject("Nicht mit dem Drucker verbunden - erst verbinden() aufrufen.");
            return;
        }
        if (sendenAufruf != null) {
            call.reject("Vorheriger Schreibvorgang noch nicht abgeschlossen.");
            return;
        }
        byte[] bytes = Base64.decode(bytesBase64, Base64.NO_WRAP);
        zielCharakteristik.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        zielCharakteristik.setValue(bytes);
        boolean geschrieben = gatt.writeCharacteristic(zielCharakteristik);
        if (!geschrieben) {
            call.reject("Senden an den Drucker fehlgeschlagen (Bluetooth-Stack hat den Schreibvorgang abgelehnt).");
            return;
        }
        sendenAufruf = call;
        // Auf das echte onCharacteristicWrite()-Signal warten statt blind eine feste Pause
        // abzuwarten - mit Timeout als Absicherung, falls der Callback auf diesem Geraet doch
        // nie feuert.
        handler.postDelayed(sendenTimeoutRunnable, SCHREIB_TIMEOUT_MS);
    }

    private void beendeSendenErfolgreich() {
        if (sendenAufruf == null) return;
        handler.removeCallbacks(sendenTimeoutRunnable);
        PluginCall aufruf = sendenAufruf;
        sendenAufruf = null;
        JSObject ergebnis = new JSObject();
        ergebnis.put("gesendet", true);
        aufruf.resolve(ergebnis);
    }

    @PluginMethod
    public void trennen(PluginCall call) {
        trenneAlteVerbindung();
        call.resolve();
    }

    private void trenneAlteVerbindung() {
        if (aktiverScanCallback != null && scanner != null) {
            scanner.stopScan(aktiverScanCallback);
            aktiverScanCallback = null;
        }
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        zielCharakteristik = null;
        handler.removeCallbacks(sendenTimeoutRunnable);
        if (sendenAufruf != null) {
            sendenAufruf.reject("Verbindung zum Drucker getrennt.");
            sendenAufruf = null;
        }
    }
}
