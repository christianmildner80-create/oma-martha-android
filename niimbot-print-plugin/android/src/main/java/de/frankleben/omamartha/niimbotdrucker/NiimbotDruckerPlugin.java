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
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

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
@CapacitorPlugin(name = "NiimbotDrucker")
public class NiimbotDruckerPlugin extends Plugin {

    private static final UUID SERVICE_UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2");
    private static final UUID CHAR_UUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long VERBINDEN_TIMEOUT_MS = 20000;
    // Kleine Pause nach jedem Schreiben (Forschungsergebnis 26.08.2026: WRITE_TYPE_NO_RESPONSE
    // liefert auf vielen Android-BLE-Stacks kein verlaessliches onCharacteristicWrite-Callback -
    // eine feste Mindestpause zwischen Paketen ist die von den Referenz-Implementierungen
    // (niimbluelib) genutzte, robustere Absicherung gegen verlorene/verschluckte Pakete).
    private static final long SCHREIB_PAUSE_MS = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic zielCharakteristik;
    private BluetoothLeScanner scanner;
    private ScanCallback aktiverScanCallback;
    private PluginCall verbindenAufruf;

    @PluginMethod
    public void verbinden(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            call.reject("Standort-Berechtigung fehlt - wird für Bluetooth-Suche benötigt, bitte in den Android-App-Einstellungen erlauben");
            return;
        }
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
                BluetoothGattCharacteristic charakteristik = dienst != null ? dienst.getCharacteristic(CHAR_UUID) : null;
                if (charakteristik == null) {
                    beendeVerbindenMitFehler("Erwarteter Bluetooth-Dienst am Drucker nicht gefunden - falsches Gerät oder anderes Modell?");
                    return;
                }
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
                if (CCCD_UUID.equals(descriptor.getUuid())) {
                    beendeVerbindenErfolgreich();
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
                JSObject daten = new JSObject();
                daten.put("bytesBase64", Base64.encodeToString(characteristic.getValue(), Base64.NO_WRAP));
                notifyListeners("antwort", daten);
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
        byte[] bytes = Base64.decode(bytesBase64, Base64.NO_WRAP);
        zielCharakteristik.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        zielCharakteristik.setValue(bytes);
        boolean geschrieben = gatt.writeCharacteristic(zielCharakteristik);
        if (!geschrieben) {
            call.reject("Senden an den Drucker fehlgeschlagen (Bluetooth-Stack hat den Schreibvorgang abgelehnt).");
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                JSObject ergebnis = new JSObject();
                ergebnis.put("gesendet", true);
                call.resolve(ergebnis);
            }
        }, SCHREIB_PAUSE_MS);
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
    }
}
