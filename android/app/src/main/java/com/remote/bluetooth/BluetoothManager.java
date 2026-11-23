package com.remote.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class BluetoothManager {

    private static final String TAG = "RemoteBT";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int DEFAULT_L2CAP_PSM = 0x1001; // Dynamic L2CAP PSM

    public interface ConnectionCallback {
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String message);
        void onMessageReceived(String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Closeable activeSocket;
    private OutputStream outStream;
    private InputStream inStream;
    private ConnectionCallback callback;

    // Swift Pair Beacon
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertiseCallback;

    private final BlockingQueue<byte[]> sendQueue = new LinkedBlockingQueue<>();
    private volatile boolean isRunning = false;
    private Thread sendThread;
    private Thread readThread;

    public void setCallback(ConnectionCallback callback) {
        this.callback = callback;
    }

    public boolean isConnected() {
        return activeSocket != null && isRunning;
    }

    /**
     * Start Microsoft Swift Pair BLE Advertisement (Manufacturer ID: 0x0006)
     * Triggers Windows 10/11 auto-discovery toast popup when nearby.
     */
    @SuppressLint("MissingPermission")
    public void startSwiftPairBeacon(BluetoothAdapter adapter) {
        if (adapter == null || !adapter.isEnabled()) return;
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                Log.w(TAG, "BLE Advertiser not supported on this device.");
                return;
            }

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();

            // Microsoft Swift Pair Beacon Payload (Manufacturer ID: 0x0006)
            // [0] = 0x03 (Scenario ID: Swift Pair)
            // [1] = 0x01 (Sub-scenario / Version 1)
            // [2] = 0xC0 (RSSI threshold: -64 dBm for natural room proximity)
            byte[] swiftPairPayload = new byte[] {
                    0x03,
                    0x01,
                    (byte) 0xC0
            };

            AdvertiseData data = new AdvertiseData.Builder()
                    .addManufacturerData(0x0006, swiftPairPayload)
                    .setIncludeDeviceName(true)
                    .build();

            advertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.d(TAG, "Swift Pair BLE Beacon active! Windows will show auto-connect toast.");
                }

                @Override
                public void onStartFailure(int errorCode) {
                    Log.w(TAG, "Swift Pair Beacon failed to start, code: " + errorCode);
                }
            };

            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (Exception e) {
            Log.e(TAG, "Error starting Swift Pair beacon: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public void stopSwiftPairBeacon() {
        if (advertiser != null && advertiseCallback != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
                Log.d(TAG, "Swift Pair BLE Beacon stopped.");
            } catch (Exception ignored) {}
            advertiseCallback = null;
        }
    }

    /**
     * Connect to Bluetooth device using Direct L2CAP or RFCOMM fallback.
     */
    @SuppressLint("MissingPermission")
    public void connect(final BluetoothDevice device) {
        disconnect();

        new Thread(() -> {
            try {
                Log.d(TAG, "Connecting to: " + device.getName() + " (" + device.getAddress() + ")");
                BluetoothSocket tempSocket = null;

                // Attempt 1: Direct L2CAP Channel (Android 10+ / API 29+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        Log.d(TAG, "Attempting direct L2CAP Channel on PSM: " + DEFAULT_L2CAP_PSM);
                        tempSocket = device.createInsecureL2capChannel(DEFAULT_L2CAP_PSM);
                        tempSocket.connect();
                        Log.d(TAG, "Connected via Direct L2CAP Channel!");
                    } catch (Exception eL2cap) {
                        Log.w(TAG, "L2CAP connection failed (" + eL2cap.getMessage() + "), falling back to RFCOMM...");
                        tempSocket = null;
                    }
                }

                // Attempt 2: Standard SPP UUID RFCOMM
                if (tempSocket == null || !tempSocket.isConnected()) {
                    try {
                        tempSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                        tempSocket.connect();
                        Log.d(TAG, "Connected via createInsecureRfcommSocketToServiceRecord");
                    } catch (Exception e1) {
                        Log.w(TAG, "SPP UUID failed: " + e1.getMessage() + ", trying direct RFCOMM channel 4...");
                        try {
                            Method m = device.getClass().getMethod("createInsecureRfcommSocket", int.class);
                            tempSocket = (BluetoothSocket) m.invoke(device, 4);
                            if (tempSocket != null) {
                                tempSocket.connect();
                                Log.d(TAG, "Connected via createInsecureRfcommSocket(4)");
                            }
                        } catch (Exception e2) {
                            Log.w(TAG, "Direct channel 4 failed: " + e2.getMessage() + ", trying secure reflection...");
                            Method m2 = device.getClass().getMethod("createRfcommSocket", int.class);
                            tempSocket = (BluetoothSocket) m2.invoke(device, 4);
                            if (tempSocket != null) {
                                tempSocket.connect();
                                Log.d(TAG, "Connected via createRfcommSocket(4)");
                            }
                        }
                    }
                }

                if (tempSocket == null || !tempSocket.isConnected()) {
                    throw new Exception("Could not establish Bluetooth channel to laptop.");
                }

                activeSocket = tempSocket;
                outStream = tempSocket.getOutputStream();
                inStream = tempSocket.getInputStream();
                isRunning = true;

                startSendLoop();
                startReadLoop();

                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onConnected(device.getName() != null ? device.getName() : device.getAddress());
                    }
                });

            } catch (final Exception e) {
                Log.e(TAG, "Connection failed: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError(e.getMessage());
                    }
                });
                disconnect();
            }
        }).start();
    }

    /**
     * Connect via BNEP Ethernet Tunnel / Bluetooth PAN (TCP/IP over Bluetooth without Wi-Fi)
     */
    public void connectTcp(final String host, final int port) {
        disconnect();

        new Thread(() -> {
            try {
                Log.d(TAG, "Connecting via BNEP TCP Tunnel to: " + host + ":" + port);
                Socket tcpSocket = new Socket();
                tcpSocket.setTcpNoDelay(true); // 7.5ms low-latency socket tuning
                tcpSocket.connect(new InetSocketAddress(host, port), 4000);

                activeSocket = tcpSocket;
                outStream = tcpSocket.getOutputStream();
                inStream = tcpSocket.getInputStream();
                isRunning = true;

                startSendLoop();
                startReadLoop();

                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onConnected("BNEP Tunnel (" + host + ")");
                    }
                });

            } catch (final Exception e) {
                Log.e(TAG, "BNEP TCP Connection failed: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError("BNEP Connection failed: " + e.getMessage());
                    }
                });
                disconnect();
            }
        }).start();
    }

    private void startSendLoop() {
        sendThread = new Thread(() -> {
            Log.d(TAG, "Send thread started.");
            while (isRunning) {
                try {
                    byte[] bytes = sendQueue.take();
                    if (outStream != null) {
                        outStream.write(bytes);
                        outStream.flush();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "TX Error: " + e.getMessage());
                    disconnect();
                    break;
                }
            }
            Log.d(TAG, "Send thread exited.");
        });
        sendThread.start();
    }

    private void startReadLoop() {
        readThread = new Thread(() -> {
            Log.d(TAG, "Read thread started.");
            byte[] buffer = new byte[2048];
            StringBuilder sb = new StringBuilder();
            while (isRunning && inStream != null) {
                try {
                    int bytes = inStream.read(buffer);
                    if (bytes == -1) {
                        Log.w(TAG, "Server closed stream (EOF).");
                        break;
                    }
                    String chunk = new String(buffer, 0, bytes, "UTF-8");
                    sb.append(chunk);
                    int nlIdx;
                    while ((nlIdx = sb.indexOf("\n")) != -1) {
                        String line = sb.substring(0, nlIdx).trim();
                        sb.delete(0, nlIdx + 1);
                        if (!line.isEmpty()) {
                            mainHandler.post(() -> {
                                if (callback != null) {
                                    callback.onMessageReceived(line);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    if (isRunning) {
                        Log.w(TAG, "Read error: " + e.getMessage());
                    }
                    break;
                }
            }
            if (isRunning) {
                disconnect();
            }
            Log.d(TAG, "Read thread exited.");
        });
        readThread.start();
    }

    /**
     * Send standard JSON command.
     */
    public void send(JSONObject json) {
        if (isConnected() && json != null) {
            try {
                byte[] bytes = (json.toString() + "\n").getBytes("UTF-8");
                sendQueue.offer(bytes);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Send ultra-low-latency 5-byte binary motion packet:
     * [0x01, dx_high, dx_low, dy_high, dy_low]
     * Bypasses JSON serialization completely for sub-millisecond trackpad/air-mouse dispatch.
     */
    public void sendBinaryMouseMove(int dx, int dy) {
        if (isConnected()) {
            byte[] packet = new byte[] {
                    0x01, // Binary motion header
                    (byte) ((dx >> 8) & 0xFF),
                    (byte) (dx & 0xFF),
                    (byte) ((dy >> 8) & 0xFF),
                    (byte) (dy & 0xFF)
            };
            sendQueue.offer(packet);
        }
    }

    public synchronized void disconnect() {
        if (!isRunning && activeSocket == null) return;
        isRunning = false;
        Log.d(TAG, "Disconnecting...");

        if (sendThread != null) {
            sendThread.interrupt();
            sendThread = null;
        }
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        sendQueue.clear();

        try {
            if (outStream != null) outStream.close();
            if (inStream != null) inStream.close();
            if (activeSocket != null) activeSocket.close();
        } catch (Exception ignored) {}

        activeSocket = null;
        outStream = null;
        inStream = null;

        mainHandler.post(() -> {
            if (callback != null) {
                callback.onDisconnected();
            }
        });
    }
}
