package com.remote.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class BluetoothManager {

    private static final String TAG = "RemoteBT";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface ConnectionCallback {
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String message);
        void onMessageReceived(String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothSocket socket;
    private OutputStream outStream;
    private InputStream inStream;
    private ConnectionCallback callback;

    private final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();
    private volatile boolean isRunning = false;
    private Thread sendThread;
    private Thread readThread;

    public void setCallback(ConnectionCallback callback) {
        this.callback = callback;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && isRunning;
    }

    @SuppressLint("MissingPermission")
    public void connect(final BluetoothDevice device) {
        disconnect();

        new Thread(() -> {
            try {
                Log.d(TAG, "Connecting to: " + device.getName() + " (" + device.getAddress() + ")");
                BluetoothSocket tempSocket = null;

                // Try 1: Standard SPP UUID
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

                if (tempSocket == null || !tempSocket.isConnected()) {
                    throw new Exception("Could not establish RFCOMM channel to laptop.");
                }

                socket = tempSocket;
                outStream = socket.getOutputStream();
                inStream = socket.getInputStream();
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

    private void startSendLoop() {
        sendThread = new Thread(() -> {
            Log.d(TAG, "Send thread started.");
            while (isRunning) {
                try {
                    String msg = sendQueue.take();
                    if (outStream != null) {
                        byte[] bytes = (msg + "\n").getBytes("UTF-8");
                        outStream.write(bytes);
                        outStream.flush();
                        Log.d(TAG, "TX -> " + msg);
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
            byte[] buffer = new byte[1024];
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
                            Log.d(TAG, "RX <- " + line);
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

    public void send(JSONObject json) {
        if (isConnected() && json != null) {
            sendQueue.offer(json.toString());
        } else {
            Log.w(TAG, "Cannot send: not connected. json=" + json);
        }
    }

    public synchronized void disconnect() {
        if (!isRunning && socket == null) return;
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
            if (socket != null) socket.close();
        } catch (Exception ignored) {}

        socket = null;
        outStream = null;
        inStream = null;

        mainHandler.post(() -> {
            if (callback != null) {
                callback.onDisconnected();
            }
        });
    }
}
