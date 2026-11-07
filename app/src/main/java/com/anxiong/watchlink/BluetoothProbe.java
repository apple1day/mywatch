package com.anxiong.watchlink;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Foreground, user-directed diagnostics. Never sends proprietary commands. */
@SuppressLint("MissingPermission")
public final class BluetoothProbe {
    public interface Listener { void onChanged(); }
    public static final class Device {
        final BluetoothDevice remote;
        String name = "未命名设备";
        String source = "";
        int rssi = 0;
        int bondState = BluetoothDevice.BOND_NONE;
        int type = BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        String advertisedServices = "";
        Device(BluetoothDevice remote) { this.remote = remote; }
        public boolean candidate() { return WatchProfile.isNameCandidate(name); }
        public String addressLabel() { return WatchProfile.maskedAddress(remote.getAddress()); }
    }

    private final Context context;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final ClassicLink classic;
    private final LinkedHashMap<String, Device> devices = new LinkedHashMap<>();
    private final ArrayList<String> events = new ArrayList<>();
    private final LinkedHashMap<String, String> info = new LinkedHashMap<>();
    private final ArrayList<String> services = new ArrayList<>();
    private final ArrayList<String> classicServices = new ArrayList<>();
    private final ArrayDeque<BluetoothGattCharacteristic> reads = new ArrayDeque<>();
    private final String reportSalt = UUID.randomUUID().toString();
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic pendingRead;
    private boolean leScanning, classicScanning, active = true, receiverRegistered;
    private boolean connected, servicesKnown;
    private String state = "准备连接";
    private Device selected;
    private Runnable timer;
    private boolean redrawPending;

    public BluetoothProbe(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        classic = new ClassicLink(new ClassicLink.Listener() {
            @Override public void onStage(String message) { state = message; changed(); }
            @Override public void onEvent(String message) { log(message); }
        });
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        // Bluetooth broadcasts originate in a privileged system process, not only system UID.
        if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else this.context.registerReceiver(receiver, filter);
        receiverRegistered = true;
        log("应用启动；仅使用公开安卓蓝牙接口。原厂账号绑定、通知与健康同步尚未实现。");
    }

    public boolean supported() { return adapter != null; }
    public void setListener(Listener value) { listener = value; }
    public void detachListener(Listener expected) { if (listener == expected) listener = null; }
    public boolean permissionsGranted() {
        if (Build.VERSION.SDK_INT >= 31) return granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT);
        return granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }
    private boolean granted(String p) { return context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }
    public boolean enabled() { try { return adapter != null && adapter.isEnabled(); } catch (SecurityException e) { return false; } }
    public boolean scanning() { return leScanning || classicScanning; }
    public boolean connected() { return connected; }
    public boolean classicConnected() { return classic.connected(); }
    public boolean classicWorking() { return classic.working(); }
    public boolean bleWorking() { return gatt != null; }
    public boolean servicesKnown() { return servicesKnown; }
    public String state() { return state; }
    public Device selected() { return selected; }
    public Map<String, String> information() { return new LinkedHashMap<>(info); }
    public List<String> serviceLines() { return new ArrayList<>(services); }
    public List<String> classicServiceLines() { return new ArrayList<>(classicServices); }
    public List<String> eventLines() { return new ArrayList<>(events); }
    public List<Device> devices() {
        ArrayList<Device> result = new ArrayList<>(devices.values());
        Collections.sort(result, (a,b) -> {
            if (a.candidate() != b.candidate()) return a.candidate() ? -1 : 1;
            if ((a == selected) != (b == selected)) return a == selected ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return result;
    }

    private void changed() {
        if (!active || redrawPending) return;
        redrawPending = true;
        main.postDelayed(() -> { redrawPending = false; if (active && listener != null) listener.onChanged(); }, 650);
    }
    private void log(String text) {
        events.add(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + text);
        if (events.size() > 240) events.remove(0);
        changed();
    }
    private void cancelTimer() { if (timer != null) { main.removeCallbacks(timer); timer = null; } }
    private void deadline(long ms, Runnable action) { cancelTimer(); timer = action; main.postDelayed(action, ms); }

    private Device observe(BluetoothDevice remote, String name, int rssi, String source) {
        if (remote == null || !permissionsGranted()) return null;
        try {
            String key = remote.getAddress();
            Device device = devices.get(key);
            if (device == null) {
                if (devices.size() >= 150) return null;
                device = new Device(remote); devices.put(key, device);
            }
            String actualName = name != null && !name.isEmpty() ? name : remote.getName();
            if (actualName != null && !actualName.isEmpty()) {
                actualName = actualName.replaceAll("[\\p{Cntrl}]", " ");
                device.name = actualName.length() > 72 ? actualName.substring(0,72) : actualName;
            }
            device.rssi = rssi;
            device.type = remote.getType(); device.bondState = remote.getBondState();
            if (!device.source.contains(source)) device.source += (device.source.isEmpty() ? "" : "、") + source;
            changed(); return device;
        } catch (SecurityException e) { log("蓝牙权限已撤回，请重新授权。"); return null; }
    }

    public void loadBonded() {
        if (!permissionsGranted() || !enabled()) return;
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) observe(d, null, 0, "系统已配对");
            log("已读取系统配对设备列表。");
        } catch (SecurityException e) { log("读取系统配对设备失败：权限不足。"); }
    }

    public void select(Device device) {
        if (device == selected) return;
        stopScan(); closeGatt(false); classic.reset(); selected = device; services.clear(); classicServices.clear(); info.clear();
        state = "已选择手表，等待连接";
        log("选中 " + device.name + "（" + device.addressLabel() + "）；名称匹配仅是线索。");
    }

    public void scanBle() {
        if (!permissionsGranted() || !enabled()) return;
        stopScan(); closeGatt(false); classic.stop();
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { state = "这台手机未提供 BLE 扫描"; log(state); return; }
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            leScanning = true; state = "正在扫描附近设备 · 15 秒";
            log("开始 BLE 扫描，显示所有设备，并标记一代名称候选。");
            deadline(15000, () -> { stopScan(); state = "扫描结束，请选择手表"; log(state); });
        } catch (Exception e) { state = "扫描未启动"; log("BLE 扫描失败：" + e.getClass().getSimpleName()); }
        changed();
    }

    public void scanClassic() {
        if (!permissionsGranted() || !enabled()) return;
        stopScan(); closeGatt(false); classic.stop();
        try {
            classicScanning = adapter.startDiscovery();
            state = classicScanning ? "正在搜索经典蓝牙 · 最长 18 秒" : "系统未接受经典蓝牙扫描";
            log(state);
            if (classicScanning) deadline(18000, () -> { stopScan(); state = "经典蓝牙扫描结束"; log(state); });
        } catch (SecurityException e) { log("经典蓝牙扫描需要附近设备权限。"); }
    }

    public void stopScan() {
        if (!scanning()) return;
        cancelTimer();
        try { if (leScanning && scanner != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        try { if (classicScanning && adapter != null) adapter.cancelDiscovery(); } catch (Exception ignored) {}
        leScanning = false; classicScanning = false; state = "扫描已停止"; changed();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { main.post(() -> receiveScan(result)); }
        @Override public void onBatchScanResults(List<ScanResult> results) { main.post(() -> { for (ScanResult r : results) receiveScan(r); }); }
        @Override public void onScanFailed(int errorCode) { main.post(() -> {
            if (!active || !leScanning) return; stopScan(); state = "扫描失败，错误码 " + errorCode; log(state);
        }); }
    };
    private void receiveScan(ScanResult result) {
        if (!active || !leScanning) return;
        ScanRecord record = result.getScanRecord();
        Device d = observe(result.getDevice(), record == null ? null : record.getDeviceName(), result.getRssi(), "BLE");
        if (d != null && record != null && record.getServiceUuids() != null) d.advertisedServices = record.getServiceUuids().toString();
    }

    public void pair() {
        if (selected == null || !permissionsGranted() || !enabled()) return;
        stopScan(); closeGatt(false); classic.stop();
        try {
            selected.bondState = selected.remote.getBondState();
            if (selected.bondState == BluetoothDevice.BOND_BONDED) { log("设备已有系统配对；这不代表已完成健康应用绑定。"); return; }
            boolean started = selected.remote.createBond();
            state = started ? "等待系统／手表确认配对" : "系统未接受配对请求";
            log(state);
        } catch (SecurityException e) { log("系统配对失败：权限不足。"); }
    }

    public void inspectClassicServices() {
        if (selected == null || !permissionsGranted() || !enabled()) return;
        stopScan();
        try {
            classicServices.clear();
            ParcelUuid[] cached = selected.remote.getUuids();
            if (cached != null) for (ParcelUuid u : cached) classicServices.add(u.toString());
            boolean sent = selected.remote.fetchUuidsWithSdp();
            log("请求经典蓝牙服务列表：" + (sent ? "已提交，等待返回" : "未提交；展示已有缓存"));
        } catch (SecurityException e) { log("经典蓝牙服务查询失败：权限不足。"); }
    }

    public void connect() {
        if (selected == null || !permissionsGranted() || !enabled()) return;
        stopScan(); classic.stop(); closeGatt(false); services.clear(); info.clear();
        try {
            gatt = selected.remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { state = "系统未创建连接"; log(state); return; }
            state = "正在建立 BLE 连接"; log(state);
            BluetoothGatt expected = gatt;
            deadline(25000, () -> { if (gatt == expected && !connected) { closeGatt(false); state = "连接超时"; log("BLE 连接超时。可尝试经典蓝牙服务查询并导出报告。"); } });
        } catch (Exception e) { closeGatt(false); state = "连接未建立"; log("BLE 连接失败：" + e.getClass().getSimpleName()); }
    }

    public void connectClassic() {
        if (selected == null || !permissionsGranted() || !enabled()) return;
        stopScan(); closeGatt(false); services.clear(); info.clear();
        try { if (adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (SecurityException ignored) {}
        classic.connect(selected.remote);
    }

    public void disconnect() { stopScan(); closeGatt(false); classic.stop(); state = "已断开连接"; log(state); }

    private void closeGatt(boolean unused) {
        cancelTimer();
        BluetoothGatt old = gatt; gatt = null;
        connected = false; servicesKnown = false; pendingRead = null; reads.clear();
        if (old != null) { try { old.disconnect(); } catch (Exception ignored) {} try { old.close(); } catch (Exception ignored) {} }
        changed();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int status, int newState) { main.post(() -> {
            if (!active || source != gatt) return;
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                cancelTimer();
                connected = true; state = "蓝牙已连接，正在查询服务";
                log("GATT 连接已建立；原厂账号绑定尚未验证。");
                try {
                    if (!source.discoverServices()) { state = "蓝牙已连接，服务查询未启动"; log(state); }
                    else deadline(20000, () -> { if (source == gatt && !servicesKnown) { closeGatt(false); state = "服务查询超时"; log(state); } });
                } catch (SecurityException e) { closeGatt(false); state = "蓝牙权限已撤回"; log(state); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt(false); state = "蓝牙已断开 · 状态 " + status; log(state);
            }
            changed();
        }); }
        @Override public void onServicesDiscovered(BluetoothGatt source, int status) { main.post(() -> {
            if (!active || source != gatt) return;
            cancelTimer(); services.clear();
            if (status != BluetoothGatt.GATT_SUCCESS) { state = "服务查询失败 · 状态 " + status; log(state); return; }
            servicesKnown = true;
            for (BluetoothGattService s : source.getServices()) {
                if (services.size() >= 510) { services.add("服务清单达到 510 行上限，后续项目未记录。"); break; }
                services.add("服务 " + s.getUuid());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (services.size() >= 510) break;
                    services.add("  特征 " + c.getUuid() + "  属性 0x" + Integer.toHexString(c.getProperties()));
                }
            }
            state = "蓝牙服务已发现";
            log("发现 " + source.getServices().size() + " 项 GATT 服务；可以读取设备公开的基础信息。");
        }); }
        @Override public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic c, int status) {
            // API 33+ invokes the value-preserving overload instead.
            if (Build.VERSION.SDK_INT < 33) receiveRead(source, c, c.getValue(), status);
        }
        @Override public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic c, byte[] value, int status) {
            receiveRead(source, c, value, status);
        }
    };

    private void receiveRead(BluetoothGatt source, BluetoothGattCharacteristic c, byte[] value, int status) {
        final byte[] copy = value == null ? null : value.clone();
        main.post(() -> {
            if (!active || source != gatt || c != pendingRead) return;
            cancelTimer(); pendingRead = null;
            String label = WatchProfile.standardLabel(c.getUuid());
            if (status == BluetoothGatt.GATT_SUCCESS) {
                info.put(label, WatchProfile.standardValue(c.getUuid(), copy)); log(label + "读取成功。");
            } else { info.put(label, "未读到 · 状态 " + status); log(label + "读取未成功，状态 " + status); }
            readNext();
        });
    }

    public void readBasicInfo() {
        if (gatt == null || !connected || !servicesKnown || pendingRead != null) return;
        reads.clear();
        addRead("180f", "2a19");
        for (String c : new String[]{"2a24", "2a26", "2a28", "2a29"}) addRead("180a", c);
        if (reads.isEmpty()) {
            log("此设备没有公开本版支持的标准电量／设备信息特征；不能据此认定手表不支持数据同步。");
            state = "基础信息需使用厂商协议"; return;
        }
        log("读取公开的标准基础信息，不发送私有控制指令。"); readNext();
    }
    private void addRead(String service, String characteristic) {
        BluetoothGattService s = gatt.getService(WatchProfile.standardUuid(service));
        BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(WatchProfile.standardUuid(characteristic));
        if (c != null && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) reads.add(c);
    }
    private void readNext() {
        if (gatt == null || !connected) return;
        if (reads.isEmpty()) { state = "基础信息读取结束"; changed(); return; }
        BluetoothGattCharacteristic c = reads.removeFirst(); pendingRead = c;
        try {
            if (!gatt.readCharacteristic(c)) { pendingRead = null; log("系统未接受读取 " + WatchProfile.standardLabel(c.getUuid())); main.post(this::readNext); return; }
            BluetoothGatt expected = gatt;
            deadline(15000, () -> { if (gatt == expected && pendingRead == c) { closeGatt(false); state = "读取超时，连接已释放"; log(state); } });
        } catch (SecurityException e) { closeGatt(false); state = "读取权限不足"; log(state); }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (!active || intent == null) return;
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int current;
                try { current = adapter == null ? BluetoothAdapter.STATE_OFF : adapter.getState(); }
                catch (SecurityException e) { return; }
                if (current == BluetoothAdapter.STATE_OFF || current == BluetoothAdapter.STATE_TURNING_OFF) {
                    stopScan(); closeGatt(false); classic.stop(); state = "蓝牙已关闭"; log(state);
                } else if (current == BluetoothAdapter.STATE_ON) { state = "蓝牙已开启，准备连接"; log(state); }
                else if (current == BluetoothAdapter.STATE_TURNING_ON) { state = "正在打开蓝牙"; changed(); }
                return;
            }
            if (!permissionsGranted()) return;
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (classicScanning) { classicScanning = false; cancelTimer(); state = "经典蓝牙扫描结束"; log(state); }
                return;
            }
            BluetoothDevice remote = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (remote == null) return;
            if (BluetoothDevice.ACTION_FOUND.equals(action) && classicScanning) {
                observe(remote, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short)0), "经典蓝牙");
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                Device d = devices.get(remote.getAddress());
                if (d == null) return;
                // Read current system state instead of trusting intent extras.
                try { d.bondState = remote.getBondState(); } catch (SecurityException e) { return; }
                if (d == selected) { state = "系统配对：" + bondLabel(d.bondState); log(state + "；应用账号绑定仍未验证。"); }
                changed();
            } else if (BluetoothDevice.ACTION_UUID.equals(action) && selected != null && remote.equals(selected.remote)) {
                classicServices.clear();
                try { ParcelUuid[] uuids = remote.getUuids(); if (uuids != null) for (ParcelUuid u : uuids) classicServices.add(u.toString()); }
                catch (SecurityException e) { log("读取经典服务列表时权限不足。"); }
                log("经典蓝牙服务列表返回，共 " + classicServices.size() + " 项。仅记录，不自动打开私有通道。");
            }
        }
    };

    public static String bondLabel(int state) {
        if (state == BluetoothDevice.BOND_BONDED) return "已配对";
        if (state == BluetoothDevice.BOND_BONDING) return "配对中";
        return "未配对";
    }
    public void pause() {
        boolean hadWork = scanning() || gatt != null || classic.working();
        stopScan(); closeGatt(false); classic.stop();
        if (hadWork) { state = "已暂停连接"; log("应用离开前台，扫描与连接已释放；返回后可手动重连。"); }
    }
    public void close() {
        pause(); active = false; classic.close(); main.removeCallbacksAndMessages(null);
        if (receiverRegistered) { context.unregisterReceiver(receiver); receiverRegistered = false; }
    }

    public String report() {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "OnePlus Watch Link").put("app_version", "0.1.0");
            root.put("generated_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(new Date()));
            root.put("phone_manufacturer", Build.MANUFACTURER).put("phone_model", Build.MODEL);
            root.put("android_release", Build.VERSION.RELEASE).put("android_sdk", Build.VERSION.SDK_INT);
            root.put("source_apk_sha256", WatchProfile.SOURCE_SHA256);
            root.put("state", state).put("gatt_connected_at_export", connected);
            root.put("classic_oneplus_transport", classic.report());
            root.put("vendor_account_binding", "NOT_IMPLEMENTED");
            root.put("health_sync", "NOT_IMPLEMENTED").put("notifications", "NOT_IMPLEMENTED");
            root.put("permissions_granted", permissionsGranted()).put("bluetooth_enabled", enabled());
            if (selected != null) {
                JSONObject device = new JSONObject();
                device.put("name", selected.name).put("address_masked", selected.addressLabel());
                device.put("session_device_id", WatchProfile.anonymousId(selected.remote.getAddress(), reportSalt));
                device.put("name_candidate_only", selected.candidate()).put("transport_type", selected.type);
                device.put("source", selected.source).put("rssi", selected.rssi);
                device.put("system_bond_state", bondLabel(selected.bondState));
                device.put("advertised_service_uuids", selected.advertisedServices); root.put("selected_device", device);
            }
            root.put("standard_information", new JSONObject(info));
            root.put("gatt_inventory", new JSONArray(services));
            root.put("classic_sdp_services", new JSONArray(classicServices));
            root.put("events", new JSONArray(events));
            root.put("scope", "Foreground connection diagnostics only; real-watch behavior has not been validated by the developer.");
            return root.toString(2);
        } catch (JSONException e) { return "{\"report_error\":\"serialization_failed\"}"; }
    }
}
