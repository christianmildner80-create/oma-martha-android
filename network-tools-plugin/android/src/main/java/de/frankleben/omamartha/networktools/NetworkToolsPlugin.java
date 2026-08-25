package de.frankleben.omamartha.networktools;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.HashSet;
import java.util.List;

@CapacitorPlugin(
    name = "NetworkTools",
    permissions = {
        @Permission(strings = { android.Manifest.permission.ACCESS_FINE_LOCATION }, alias = "standort")
    }
)
public class NetworkToolsPlugin extends Plugin {

    @PluginMethod
    public void scanWifi(PluginCall call) {
        if (getPermissionState("standort") != PermissionState.GRANTED) {
            requestPermissionForAlias("standort", call, "scanWifiCallback");
            return;
        }
        durchfuehrenScan(call);
    }

    @PermissionCallback
    private void scanWifiCallback(PluginCall call) {
        if (getPermissionState("standort") == PermissionState.GRANTED) {
            durchfuehrenScan(call);
        } else {
            call.reject("Standort-Berechtigung wird für den WLAN-Scan benötigt");
        }
    }

    private void durchfuehrenScan(PluginCall call) {
        WifiManager wifiManager = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }
        wifiManager.startScan();

        HashSet<String> gesehen = new HashSet<>();
        JSArray netzwerke = new JSArray();
        List<ScanResult> ergebnisse = wifiManager.getScanResults();
        for (ScanResult ergebnis : ergebnisse) {
            if (ergebnis.SSID == null || ergebnis.SSID.isEmpty() || !gesehen.add(ergebnis.SSID)) continue;
            JSObject eintrag = new JSObject();
            eintrag.put("ssid", ergebnis.SSID);
            eintrag.put("bssid", ergebnis.BSSID);
            eintrag.put("level", ergebnis.level);
            eintrag.put("verschluesselt", ergebnis.capabilities.contains("WPA") || ergebnis.capabilities.contains("WEP"));
            netzwerke.put(eintrag);
        }

        JSObject rueckgabe = new JSObject();
        rueckgabe.put("netzwerke", netzwerke);
        call.resolve(rueckgabe);
    }

    @PluginMethod
    public void connectWifi(PluginCall call) {
        String ssid = call.getString("ssid");
        if (ssid == null || ssid.isEmpty()) {
            call.reject("ssid fehlt");
            return;
        }
        String passwort = call.getString("passwort");

        WifiManager wifiManager = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiConfiguration konfiguration = new WifiConfiguration();
        konfiguration.SSID = "\"" + ssid + "\"";
        if (passwort == null || passwort.isEmpty()) {
            konfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            konfiguration.preSharedKey = "\"" + passwort + "\"";
        }

        int netzwerkId = wifiManager.addNetwork(konfiguration);
        if (netzwerkId == -1) {
            call.reject("Netzwerk konnte nicht hinzugefügt werden");
            return;
        }
        wifiManager.disconnect();
        wifiManager.enableNetwork(netzwerkId, true);
        wifiManager.reconnect();

        JSObject rueckgabe = new JSObject();
        rueckgabe.put("erfolg", true);
        call.resolve(rueckgabe);
    }
}
