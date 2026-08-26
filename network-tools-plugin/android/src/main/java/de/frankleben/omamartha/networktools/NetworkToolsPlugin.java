package de.frankleben.omamartha.networktools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(name = "NetworkTools")
public class NetworkToolsPlugin extends Plugin {

    @PluginMethod
    public void scanWifi(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            call.reject("Standort-Berechtigung fehlt - bitte in den Android-App-Einstellungen für Claude/Oma Martha den Standort-Zugriff erlauben");
            return;
        }

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

    // Eigene Verbindungsdaten: SSID/Signal/Geschwindigkeit vom WifiManager, IP/Gateway/DNS vom
    // DHCP-Lease. Braucht keine zusaetzliche Berechtigung ueber scanWifi hinaus.
    @PluginMethod
    public void netzwerkInfo(PluginCall call) {
        WifiManager wifiManager = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        DhcpInfo dhcp = wifiManager.getDhcpInfo();

        JSObject rueckgabe = new JSObject();

        String ssid = wifiInfo.getSSID();
        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        rueckgabe.put("ssid", ssid);
        rueckgabe.put("bssid", wifiInfo.getBSSID());
        rueckgabe.put("rssi", wifiInfo.getRssi());
        rueckgabe.put("linkSpeedMbps", wifiInfo.getLinkSpeed());
        int frequenzMhz = wifiInfo.getFrequency();
        rueckgabe.put("frequenzMhz", frequenzMhz);
        rueckgabe.put("band", frequenzMhz >= 4900 ? "5 GHz" : "2.4 GHz");

        if (dhcp != null) {
            rueckgabe.put("eigeneIp", intZuIp(dhcp.ipAddress));
            rueckgabe.put("subnetzmaske", intZuIp(dhcp.netmask));
            rueckgabe.put("gateway", intZuIp(dhcp.gateway));
            rueckgabe.put("dns1", intZuIp(dhcp.dns1));
            rueckgabe.put("dns2", dhcp.dns2 == 0 ? null : intZuIp(dhcp.dns2));
            rueckgabe.put("dhcpServer", intZuIp(dhcp.serverAddress));
            rueckgabe.put("leaseSekunden", dhcp.leaseDuration);
        }

        call.resolve(rueckgabe);
    }

    // Findet aktive Geraete im eigenen /24-Subnetz: kurzer Verbindungsversuch auf Port 80 zu
    // jeder Adresse zwingt den Kernel zur ARP-Aufloesung (auch bei "Connection refused" - der
    // Host musste dafuer antworten), danach steht die IP->MAC-Zuordnung in /proc/net/arp. Braucht
    // keinen Root. Deckt bewusst nur /24 ab (Standardfall bei Heim-/Imbiss-Netzen).
    @PluginMethod
    public void geraeteSuchen(PluginCall call) {
        WifiManager wifiManager = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        DhcpInfo dhcp = wifiManager.getDhcpInfo();
        if (dhcp == null || dhcp.ipAddress == 0) {
            call.reject("Keine aktive WLAN-Verbindung mit IP-Adresse gefunden");
            return;
        }

        String eigeneIp = intZuIp(dhcp.ipAddress);
        String praefix = eigeneIp.substring(0, eigeneIp.lastIndexOf('.') + 1);

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch fertig = new CountDownLatch(254);
        for (int i = 1; i <= 254; i++) {
            final String ziel = praefix + i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    Socket socket = new Socket();
                    try {
                        socket.connect(new InetSocketAddress(ziel, 80), 300);
                    } catch (Exception ignoriert) {
                        // Verbindung abgelehnt/Timeout ist erwartet - Ziel war fuer ARP trotzdem "gesehen"
                        // (oder eben nicht erreichbar, dann taucht es unten in /proc/net/arp nicht auf).
                    } finally {
                        try { socket.close(); } catch (Exception ignoriert) { }
                        fertig.countDown();
                    }
                }
            });
        }
        try {
            fertig.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignoriert) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();

        JSArray geraete = new JSArray();
        try (BufferedReader leser = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String zeile;
            boolean ersteZeile = true;
            while ((zeile = leser.readLine()) != null) {
                if (ersteZeile) { ersteZeile = false; continue; }
                String[] spalten = zeile.trim().split("\\s+");
                if (spalten.length < 6) continue;
                String ip = spalten[0];
                String flags = spalten[2];
                String mac = spalten[3];
                if (!ip.startsWith(praefix)) continue;
                if ("0x0".equals(flags)) continue;
                if (mac == null || mac.equals("00:00:00:00:00:00")) continue;

                JSObject eintrag = new JSObject();
                eintrag.put("ip", ip);
                eintrag.put("mac", mac.toUpperCase(Locale.ROOT));
                eintrag.put("hersteller", herstellerVonMac(mac));
                eintrag.put("istEigenesGeraet", ip.equals(eigeneIp));
                geraete.put(eintrag);
            }
        } catch (Exception fehler) {
            call.reject("Geräte-Scan fehlgeschlagen: " + fehler.getMessage());
            return;
        }

        JSObject rueckgabe = new JSObject();
        rueckgabe.put("geraete", geraete);
        call.resolve(rueckgabe);
    }

    private String intZuIp(int adresse) {
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                (adresse & 0xff),
                (adresse >> 8 & 0xff),
                (adresse >> 16 & 0xff),
                (adresse >> 24 & 0xff));
    }

    // Nur eine kuratierte Auswahl haeufiger Hersteller (MAC-OUI = erste 3 Byte) - keine
    // vollstaendige IEEE-Liste (die haette zehntausende Eintraege). Reicht fuer eine grobe
    // Einordnung im Heim-/Imbiss-Alltag, kein Anspruch auf Vollstaendigkeit.
    private static final Map<String, String> OUI_HERSTELLER = new HashMap<>();
    static {
        OUI_HERSTELLER.put("00:1A:11", "Google");
        OUI_HERSTELLER.put("F4:F5:D8", "Google");
        OUI_HERSTELLER.put("3C:5A:B4", "Google");
        OUI_HERSTELLER.put("A4:77:33", "Google Nest");
        OUI_HERSTELLER.put("00:17:88", "Philips Hue");
        OUI_HERSTELLER.put("EC:B5:FA", "Philips");
        OUI_HERSTELLER.put("A0:CE:C8", "Apple");
        OUI_HERSTELLER.put("F0:18:98", "Apple");
        OUI_HERSTELLER.put("AC:DE:48", "Apple");
        OUI_HERSTELLER.put("F4:5C:89", "Apple");
        OUI_HERSTELLER.put("3C:22:FB", "Apple");
        OUI_HERSTELLER.put("BC:92:6B", "Apple");
        OUI_HERSTELLER.put("00:1B:63", "Apple");
        OUI_HERSTELLER.put("D0:03:4B", "Apple");
        OUI_HERSTELLER.put("28:6A:BA", "Samsung");
        OUI_HERSTELLER.put("8C:71:F8", "Samsung");
        OUI_HERSTELLER.put("F8:04:2E", "Samsung");
        OUI_HERSTELLER.put("5C:0A:5B", "Samsung");
        OUI_HERSTELLER.put("00:16:6C", "Samsung");
        OUI_HERSTELLER.put("D8:97:BA", "AVM (FritzBox)");
        OUI_HERSTELLER.put("00:1C:C0", "AVM (FritzBox)");
        OUI_HERSTELLER.put("3C:37:12", "AVM (FritzBox)");
        OUI_HERSTELLER.put("9C:C7:A6", "AVM (FritzBox)");
        OUI_HERSTELLER.put("E0:28:6D", "AVM (FritzBox)");
        OUI_HERSTELLER.put("C8:0E:14", "TP-Link");
        OUI_HERSTELLER.put("50:C7:BF", "TP-Link");
        OUI_HERSTELLER.put("F4:F2:6D", "TP-Link");
        OUI_HERSTELLER.put("EC:08:6B", "TP-Link");
        OUI_HERSTELLER.put("00:0F:66", "D-Link");
        OUI_HERSTELLER.put("1C:BD:B9", "D-Link");
        OUI_HERSTELLER.put("00:26:5A", "D-Link");
        OUI_HERSTELLER.put("A0:63:91", "Netgear");
        OUI_HERSTELLER.put("20:E5:2A", "Netgear");
        OUI_HERSTELLER.put("2C:B0:5D", "Netgear");
        OUI_HERSTELLER.put("F4:92:BF", "Ubiquiti");
        OUI_HERSTELLER.put("24:5A:4C", "Ubiquiti");
        OUI_HERSTELLER.put("DC:9F:DB", "Ubiquiti");
        OUI_HERSTELLER.put("18:E8:29", "Espressif (ESP8266/32, z.B. Smart-Stecker)");
        OUI_HERSTELLER.put("24:6F:28", "Espressif (ESP8266/32, z.B. Smart-Stecker)");
        OUI_HERSTELLER.put("30:AE:A4", "Espressif (ESP8266/32, z.B. Smart-Stecker)");
        OUI_HERSTELLER.put("A4:CF:12", "Espressif (ESP8266/32, z.B. Smart-Stecker)");
        OUI_HERSTELLER.put("EC:FA:BC", "Espressif (ESP8266/32, z.B. Smart-Stecker)");
        OUI_HERSTELLER.put("B8:27:EB", "Raspberry Pi");
        OUI_HERSTELLER.put("DC:A6:32", "Raspberry Pi");
        OUI_HERSTELLER.put("E4:5F:01", "Raspberry Pi");
        OUI_HERSTELLER.put("D8:3A:DD", "Raspberry Pi");
        OUI_HERSTELLER.put("74:C2:46", "Zebra Technologies");
        OUI_HERSTELLER.put("00:15:70", "Zebra Technologies");
        OUI_HERSTELLER.put("2C:0D:A7", "Zebra Technologies");
        OUI_HERSTELLER.put("A4:11:62", "Zebra Technologies");
        OUI_HERSTELLER.put("44:65:0D", "Amazon (Echo/Fire)");
        OUI_HERSTELLER.put("68:37:E9", "Amazon (Echo/Fire)");
        OUI_HERSTELLER.put("F0:81:73", "Amazon (Echo/Fire)");
        OUI_HERSTELLER.put("00:71:47", "Amazon (Echo/Fire)");
        OUI_HERSTELLER.put("00:0C:29", "VMware (virtuelle Maschine)");
        OUI_HERSTELLER.put("08:00:27", "VirtualBox (virtuelle Maschine)");
        OUI_HERSTELLER.put("B8:27:EB", "Raspberry Pi");
        OUI_HERSTELLER.put("3C:71:BF", "Microsoft (Surface/Xbox)");
        OUI_HERSTELLER.put("00:50:F2", "Microsoft");
        OUI_HERSTELLER.put("7C:1E:52", "Microsoft (Xbox)");
        OUI_HERSTELLER.put("00:1F:A7", "Sony (PlayStation)");
        OUI_HERSTELLER.put("00:19:C5", "Sony (PlayStation)");
        OUI_HERSTELLER.put("BC:60:A7", "Sony (PlayStation)");
        OUI_HERSTELLER.put("00:1B:EA", "Nintendo");
        OUI_HERSTELLER.put("2C:10:C1", "Nintendo (Switch)");
        OUI_HERSTELLER.put("98:B6:E9", "Nintendo (Switch)");
        OUI_HERSTELLER.put("48:D6:D5", "Sonos");
        OUI_HERSTELLER.put("94:9F:3E", "Sonos");
        OUI_HERSTELLER.put("00:0E:58", "Sonos");
        OUI_HERSTELLER.put("B4:75:0E", "IKEA (Tradfri)");
        OUI_HERSTELLER.put("EC:B5:1E", "Xiaomi");
        OUI_HERSTELLER.put("34:CE:00", "Xiaomi");
        OUI_HERSTELLER.put("64:09:80", "Xiaomi");
        OUI_HERSTELLER.put("28:6C:07", "Huawei");
        OUI_HERSTELLER.put("00:E0:FC", "Huawei");
        OUI_HERSTELLER.put("48:46:FB", "Huawei");
    }

    private String herstellerVonMac(String mac) {
        if (mac == null || mac.length() < 8) return "unbekannt";
        String praefix = mac.substring(0, 8).toUpperCase(Locale.ROOT);
        String treffer = OUI_HERSTELLER.get(praefix);
        return treffer != null ? treffer : "unbekannt";
    }
}
