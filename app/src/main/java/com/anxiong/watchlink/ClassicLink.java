package com.anxiong.watchlink;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent transport probe reconstructed from observable API/UUID facts. */
@SuppressLint("MissingPermission")
public final class ClassicLink {
    public interface Listener { void onStage(String text); void onEvent(String text); }
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Listener listener;
    private volatile int generation;
    private boolean closed, commandOpen, dataOpen, commandObserved, dataObserved, attempted;
    private int commandBytes, dataBytes;
    private BluetoothSocket commandSocket, dataSocket;
    private Runnable timeout;
    private String result = "NOT_ATTEMPTED";

    public ClassicLink(Listener listener) { this.listener = listener; }
    public boolean connected() { return commandOpen && dataOpen; }
    public boolean working() { return commandSocket != null || dataSocket != null; }

    public void connect(BluetoothDevice device) {
        stop(); attempted = true; commandObserved = false; dataObserved = false;
        commandBytes = dataBytes = 0; result = "CONNECTING";
        final int token = generation;
        listener.onStage("正在连接一代经典蓝牙主通道");
        try {
            commandSocket = device.createRfcommSocketToServiceRecord(WatchProfile.COMMAND_UUID);
            BluetoothSocket selectedSocket = commandSocket;
            timeout = () -> { if (valid(token)) fail("经典蓝牙连接超时，请确认手表上的配对提示", "TIMEOUT"); };
            ui.postDelayed(timeout, 40000);
            io.execute(() -> {
                try {
                    selectedSocket.connect();
                    ui.post(() -> {
                        if (!valid(token)) { closeSocket(selectedSocket); return; }
                        commandOpen = true; commandObserved = true;
                        listener.onStage("主通道已连接，正在准备数据通道");
                        listener.onEvent("经典主通道 secure RFCOMM 已连接；尚未进行原厂业务认证。");
                        readOnly(selectedSocket, token, true);
                        ui.postDelayed(() -> openData(device, token), 1000);
                    });
                } catch (Exception e) { failure(token, "主通道", e); }
            });
        } catch (Exception e) { fail("无法创建经典蓝牙通道：" + e.getClass().getSimpleName(), "CREATE_FAILED"); }
    }

    private boolean valid(int token) { return !closed && token == generation; }
    private void openData(BluetoothDevice device, int token) {
        if (!valid(token) || !commandOpen) return;
        listener.onStage("正在连接一代经典蓝牙数据通道");
        try {
            dataSocket = device.createRfcommSocketToServiceRecord(WatchProfile.DATA_UUID);
            BluetoothSocket selectedSocket = dataSocket;
            io.execute(() -> {
                try {
                    selectedSocket.connect();
                    ui.post(() -> {
                        if (!valid(token)) { closeSocket(selectedSocket); return; }
                        dataOpen = true; dataObserved = true; result = "TRANSPORT_CONNECTED";
                        if (timeout != null) ui.removeCallbacks(timeout);
                        listener.onStage("一代双通道已连接 · 原厂绑定待适配");
                        listener.onEvent("经典主／数据通道均已连通。本版不发送账号、认证或控制指令。");
                        readOnly(selectedSocket, token, false);
                    });
                } catch (Exception e) { failure(token, "数据通道", e); }
            });
        } catch (Exception e) { failure(token, "数据通道", e); }
    }

    private void readOnly(BluetoothSocket socket, int token, boolean command) {
        io.execute(() -> {
            byte[] buffer = new byte[512];
            int total = 0;
            try {
                InputStream input = socket.getInputStream();
                while (valid(token)) {
                    int n = input.read(buffer, 0, Math.min(buffer.length, 16384 - total));
                    if (n < 0) throw new IOException("EOF");
                    if (n == 0) continue;
                    total += n;
                    final int observed = total;
                    ui.post(() -> {
                        if (!valid(token)) return;
                        if (command) commandBytes = observed; else dataBytes = observed;
                        if (observed == n) listener.onEvent((command ? "主通道" : "数据通道") + "收到数据；仅统计字节数，不保存原始内容。");
                        if (observed >= 16384) fail("已达到本次被动接收上限，连接已释放", "READ_LIMIT_REACHED");
                    });
                    if (total >= 16384) break;
                }
            } catch (Exception e) { failure(token, command ? "主通道" : "数据通道", e); }
        });
    }

    private void failure(int token, String channel, Exception e) {
        ui.post(() -> { if (valid(token)) fail(channel + "未保持连接（" + e.getClass().getSimpleName() + "）", "CHANNEL_CLOSED_OR_FAILED"); });
    }
    private void fail(String message, String code) {
        stop(); result = code; listener.onStage(message);
        listener.onEvent(message + "。无数据或断开不能单独证明设备不支持；仍需完整业务握手。");
    }
    private static void closeSocket(BluetoothSocket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) {} }

    /** Cancels blocked connects/reads via socket close and isolates stale callbacks. */
    public void stop() {
        generation++; ui.removeCallbacksAndMessages(null); timeout = null;
        BluetoothSocket oldCommand = commandSocket, oldData = dataSocket;
        commandSocket = null; dataSocket = null; commandOpen = dataOpen = false;
        closeSocket(oldCommand); closeSocket(oldData);
        if ("CONNECTING".equals(result) || "TRANSPORT_CONNECTED".equals(result)) result = "STOPPED";
    }
    public void close() { stop(); closed = true; io.shutdownNow(); }
    public void reset() {
        stop(); attempted = false; commandObserved = dataObserved = false;
        commandBytes = dataBytes = 0; result = "NOT_ATTEMPTED";
    }
    public JSONObject report() {
        JSONObject report = new JSONObject();
        try {
            report.put("attempted", attempted).put("result", result);
            report.put("command_uuid", WatchProfile.COMMAND_UUID).put("data_uuid", WatchProfile.DATA_UUID);
            report.put("mode", "secure_RFCOMM_passive_receive_no_application_writes");
            report.put("command_connected_at_export", commandOpen).put("data_connected_at_export", dataOpen);
            report.put("command_connection_observed_this_attempt", commandObserved).put("data_connection_observed_this_attempt", dataObserved);
            report.put("command_received_bytes", commandBytes).put("data_received_bytes", dataBytes);
            report.put("vendor_authentication", "NOT_IMPLEMENTED");
        } catch (Exception ignored) {}
        return report;
    }
}
