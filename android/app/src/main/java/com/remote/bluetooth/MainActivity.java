package com.remote.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.text.InputType;
import android.view.Surface;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQ_CODE = 1001;
    private static final String PREFS_NAME = "RemotePrefs";
    private static final String KEY_DARK_MODE = "isDarkMode";

    private View rootLayout, headerCard;
    private View statusIndicator;
    private TextView tvStatus, tvTelemetry;
    private Spinner spDevices;
    private Button btnConnect, btnThemeToggle;

    // Tabs
    private Button btnTabTouchpad, btnTabMedia, btnTabShortcuts, btnTabApps, btnTabKeyboard;
    private View viewTouchpad, viewMedia, viewShortcuts, viewApps, viewKeyboard;
    private int currentTabId = R.id.btnTabTouchpad;

    // Touchpad Views
    private View touchpadSurface, scrollSurface;
    private Button btnLeftClick, btnRightClick, btnAirMouse;
    private TextView tvTouchpadHint;

    // Sensors & Air Mouse
    private SensorManager sensorManager;
    private Sensor gyroSensor;
    private boolean isAirMouseActive = false;

    // App Launcher Views
    private EditText etAppSearch;
    private Button btnRefreshApps;
    private GridView gvApps;
    private TextView tvEmptyApps;
    private final List<String> allApps = new ArrayList<>();
    private final List<String> filteredApps = new ArrayList<>();
    private final Map<String, Bitmap> appIcons = new HashMap<>();
    private final Set<String> loadingApps = new HashSet<>();
    private final ExecutorService iconLoader = Executors.newFixedThreadPool(3);
    private ArrayAdapter<String> appsAdapter;

    // Keypad Views
    private Button btnOpenPhoneKeypad, btnSendText;
    private EditText etTextInput;
    private boolean isUpdatingKeypadText = false;

    // Theme state
    private boolean isDarkMode = true;
    private SharedPreferences prefs;

    // Bluetooth
    private BluetoothAdapter btAdapter;
    private final BluetoothManager btManager = new BluetoothManager();
    private final List<BluetoothDevice> bondedDevicesList = new ArrayList<>();
    private final List<String> bondedNamesList = new ArrayList<>();

    // Touch & Multi-finger gesture tracking
    private float lastX, lastY;
    private float startX, startY;
    private float mouseAccumX = 0f, mouseAccumY = 0f;
    private long touchStartTime;
    private long lastTapTime = 0;
    private int maxPointersInGesture = 1;
    private float initialPinchDistance = 0f;
    private float lastScrollY = 0f;
    private static final float TOUCH_SENSITIVITY = 1.9f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isDarkMode = prefs.getBoolean(KEY_DARK_MODE, true);

        setupLayout();
        checkPermissionsAndLoadDevices();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setupLayout();
        loadPairedDevices();
    }

    private void setupLayout() {
        setContentView(R.layout.activity_main);
        initViews();
        setupTabs();
        setupTouchpad();
        setupMediaControls();
        setupShortcuts();
        setupAppLauncher();
        setupKeyboardControls();
        switchTab(currentTabId);
        applyTheme();
        updateStatusUI();
    }

    private void initViews() {
        rootLayout = findViewById(R.id.rootLayout);
        headerCard = findViewById(R.id.headerCard);
        statusIndicator = findViewById(R.id.statusIndicator);
        tvStatus = findViewById(R.id.tvStatus);
        spDevices = findViewById(R.id.spDevices);
        btnConnect = findViewById(R.id.btnConnect);
        btnThemeToggle = findViewById(R.id.btnThemeToggle);

        btnTabTouchpad = findViewById(R.id.btnTabTouchpad);
        btnTabMedia = findViewById(R.id.btnTabMedia);
        btnTabShortcuts = findViewById(R.id.btnTabShortcuts);
        btnTabApps = findViewById(R.id.btnTabApps);
        btnTabKeyboard = findViewById(R.id.btnTabKeyboard);

        viewTouchpad = findViewById(R.id.viewTouchpad);
        viewMedia = findViewById(R.id.viewMedia);
        viewShortcuts = findViewById(R.id.viewShortcuts);
        viewApps = findViewById(R.id.viewApps);
        viewKeyboard = findViewById(R.id.viewKeyboard);

        touchpadSurface = findViewById(R.id.touchpadSurface);
        scrollSurface = findViewById(R.id.scrollSurface);
        btnLeftClick = findViewById(R.id.btnLeftClick);
        btnRightClick = findViewById(R.id.btnRightClick);
        btnAirMouse = findViewById(R.id.btnAirMouse);
        tvTouchpadHint = findViewById(R.id.tvTouchpadHint);
        tvTelemetry = findViewById(R.id.tvTelemetry);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }

        etAppSearch = findViewById(R.id.etAppSearch);
        btnRefreshApps = findViewById(R.id.btnRefreshApps);
        gvApps = findViewById(R.id.gvApps);
        tvEmptyApps = findViewById(R.id.tvEmptyApps);

        btnOpenPhoneKeypad = findViewById(R.id.btnOpenPhoneKeypad);
        btnSendText = findViewById(R.id.btnSendText);
        etTextInput = findViewById(R.id.etTextInput);

        btAdapter = BluetoothAdapter.getDefaultAdapter();

        btnThemeToggle.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            isDarkMode = !isDarkMode;
            prefs.edit().putBoolean(KEY_DARK_MODE, isDarkMode).apply();
            applyTheme();
            updateStatusUI();
        });

        btManager.setCallback(new BluetoothManager.ConnectionCallback() {
            @Override
            public void onConnected(String deviceName) {
                updateStatusUI();
                vibrate(40);
                requestAppList();
            }

            @Override
            public void onDisconnected() {
                updateStatusUI();
            }

            @Override
            public void onError(String message) {
                updateStatusUI();
                Toast.makeText(MainActivity.this, "Connection: " + message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onMessageReceived(String message) {
                handleIncomingMessage(message);
            }
        });

        btnConnect.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (btManager.isConnected()) {
                btManager.disconnect();
            } else {
                int pos = spDevices.getSelectedItemPosition();
                if (pos >= 0 && pos < bondedDevicesList.size()) {
                    BluetoothDevice device = bondedDevicesList.get(pos);
                    statusIndicator.setBackground(createDotDrawable(Color.parseColor("#F59E0B")));
                    tvStatus.setText("CONNECTING...");
                    btManager.connect(device);
                } else {
                    Toast.makeText(this, "No paired laptop found in list", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void handleIncomingMessage(String message) {
        try {
            JSONObject obj = new JSONObject(message);
            if (obj.has("apps")) {
                JSONArray arr = obj.getJSONArray("apps");
                allApps.clear();
                for (int i = 0; i < arr.length(); i++) {
                    allApps.add(arr.getString(i));
                }
                filterApps(etAppSearch != null ? etAppSearch.getText().toString() : "");
            }
            if (obj.has("t")) {
                String t = obj.getString("t");
                if ("telemetry".equals(t)) {
                    int cpu = obj.optInt("cpu", 0);
                    int ram = obj.optInt("ram", 0);
                    int bat = obj.optInt("bat", 0);
                    int ac = obj.optInt("ac", 0);
                    updateTelemetryUI(cpu, ram, bat, ac);
                } else if ("clip_data".equals(t)) {
                    String text = obj.optString("text", "");
                    setAndroidClipboard(text);
                }
            }
        } catch (Exception ignored) {}
    }

    private void requestAppList() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "get_apps");
            btManager.send(obj);
        } catch (Exception ignored) {}
    }

    private void applyTheme() {
        btnThemeToggle.setText(isDarkMode ? "LIGHT" : "DARK");

        int bg = isDarkMode ? Color.parseColor("#080808") : Color.parseColor("#F2F2F7");
        int cardBg = isDarkMode ? Color.parseColor("#121214") : Color.parseColor("#FFFFFF");
        int border = isDarkMode ? Color.parseColor("#26262A") : Color.parseColor("#D1D1D6");
        int textPri = isDarkMode ? Color.parseColor("#F5F5F7") : Color.parseColor("#000000");
        int textSec = isDarkMode ? Color.parseColor("#8A8A8E") : Color.parseColor("#636366");
        int touchBg = isDarkMode ? Color.parseColor("#0D0D0E") : Color.parseColor("#E5E5EA");
        int touchBorder = isDarkMode ? Color.parseColor("#222225") : Color.parseColor("#C7C7CC");
        int scrollBg = isDarkMode ? Color.parseColor("#18181B") : Color.parseColor("#DCDCE0");

        if (rootLayout != null) rootLayout.setBackgroundColor(bg);
        if (headerCard != null) headerCard.setBackground(createBoxDrawable(cardBg, border, 10));

        if (tvStatus != null) tvStatus.setTextColor(textPri);
        if (tvTouchpadHint != null) tvTouchpadHint.setTextColor(textSec);
        if (tvEmptyApps != null) tvEmptyApps.setTextColor(textSec);

        if (touchpadSurface != null) touchpadSurface.setBackground(createBoxDrawable(touchBg, touchBorder, 12));
        if (scrollSurface != null) scrollSurface.setBackground(createBoxDrawable(scrollBg, border, 12));

        applyButtonTheme(btnThemeToggle, false);
        applyButtonTheme(btnLeftClick, false);
        applyButtonTheme(btnRightClick, false);

        int[] regularBtnIds = {
            R.id.btnVolUp, R.id.btnVolDown, R.id.btnMute, R.id.btnPrev, R.id.btnNext,
            R.id.btnSlidePrev, R.id.btnSlideNext, R.id.btnF5, R.id.btnEsc,
            R.id.btnUnlock, R.id.btnAirMouse, R.id.btnSetPin, R.id.btnScreenOff,
            R.id.btnCopyFromPc, R.id.btnPasteToPc, R.id.btnSnip, R.id.btnMicMute, R.id.btnTaskMgr,
            R.id.btnSleep, R.id.btnRestart, R.id.btnShutdown,
            R.id.btnLock, R.id.btnDesktop, R.id.btnAltTab, R.id.btnTaskView,
            R.id.btnSearch, R.id.btnActionCenter, R.id.btnVDeskLeft, R.id.btnVDeskRight,
            R.id.btnLaunchTerminal, R.id.btnLaunchExplorer,
            R.id.btnCopy, R.id.btnPaste, R.id.btnUndo,
            R.id.btnKeyEnter, R.id.btnKeyBackspace, R.id.btnKeySpace, R.id.btnKeyEsc,
            R.id.btnKeyTab, R.id.btnKeyWin, R.id.btnKeyDel,
            R.id.btnKeyUp, R.id.btnKeyDown, R.id.btnKeyLeft, R.id.btnKeyRight,
            R.id.btnRefreshApps
        };

        for (int id : regularBtnIds) {
            View v = findViewById(id);
            if (v instanceof Button) {
                applyButtonTheme((Button) v, false);
            }
        }

        Button btnPlay = findViewById(R.id.btnPlayPause);
        if (btnPlay != null) applyButtonTheme(btnPlay, true);

        Button btnUnlock = findViewById(R.id.btnUnlock);
        if (btnUnlock != null) applyButtonTheme(btnUnlock, true);

        if (btnSendText != null) applyButtonTheme(btnSendText, true);
        if (btnOpenPhoneKeypad != null) applyButtonTheme(btnOpenPhoneKeypad, true);

        if (etTextInput != null) {
            etTextInput.setBackground(createBoxDrawable(cardBg, border, 8));
            etTextInput.setTextColor(textPri);
            etTextInput.setHintTextColor(textSec);
        }

        if (etAppSearch != null) {
            etAppSearch.setBackground(createBoxDrawable(cardBg, border, 8));
            etAppSearch.setTextColor(textPri);
            etAppSearch.setHintTextColor(textSec);
        }

        int[] labelIds = {
            R.id.tvSectionVol, R.id.tvSectionPlayback, R.id.tvSectionPres
        };
        for (int id : labelIds) {
            TextView tv = findViewById(id);
            if (tv != null) tv.setTextColor(textSec);
        }

        View c1 = findViewById(R.id.cardMediaCol1);
        View c2 = findViewById(R.id.cardMediaCol2);
        View c3 = findViewById(R.id.cardMediaCol3);
        if (c1 != null) c1.setBackground(createBoxDrawable(cardBg, border, 10));
        if (c2 != null) c2.setBackground(createBoxDrawable(cardBg, border, 10));
        if (c3 != null) c3.setBackground(createBoxDrawable(cardBg, border, 10));

        if (appsAdapter != null) appsAdapter.notifyDataSetChanged();
        switchTab(currentTabId);
    }

    private void applyButtonTheme(Button btn, boolean isProminent) {
        if (btn == null) return;
        int btnBg, btnText, btnBorder;

        if (isProminent) {
            btnBg = isDarkMode ? Color.parseColor("#FFFFFF") : Color.parseColor("#000000");
            btnText = isDarkMode ? Color.parseColor("#000000") : Color.parseColor("#FFFFFF");
            btnBorder = btnBg;
        } else {
            btnBg = isDarkMode ? Color.parseColor("#141416") : Color.parseColor("#FFFFFF");
            btnText = isDarkMode ? Color.parseColor("#F5F5F7") : Color.parseColor("#1C1C1E");
            btnBorder = isDarkMode ? Color.parseColor("#26262A") : Color.parseColor("#D1D1D6");
        }

        btn.setBackground(createBoxDrawable(btnBg, btnBorder, 8));
        btn.setTextColor(btnText);
    }

    private void updateStatusUI() {
        if (tvStatus == null || btnConnect == null) return;

        if (btManager.isConnected()) {
            statusIndicator.setBackground(createDotDrawable(Color.parseColor("#22C55E")));
            tvStatus.setText("CONNECTED");
            btnConnect.setText("DISCONNECT");

            int disconnectBorder = Color.parseColor("#EF4444");
            int disconnectBg = isDarkMode ? Color.parseColor("#200808") : Color.parseColor("#FFF0F0");
            btnConnect.setBackground(createBoxDrawable(disconnectBg, disconnectBorder, 8));
            btnConnect.setTextColor(Color.parseColor("#EF4444"));
        } else {
            statusIndicator.setBackground(createDotDrawable(Color.parseColor("#EF4444")));
            tvStatus.setText("DISCONNECTED");
            btnConnect.setText("CONNECT");

            int connBg = isDarkMode ? Color.parseColor("#FFFFFF") : Color.parseColor("#000000");
            int connText = isDarkMode ? Color.parseColor("#000000") : Color.parseColor("#FFFFFF");
            btnConnect.setBackground(createBoxDrawable(connBg, connBg, 8));
            btnConnect.setTextColor(connText);

            if (tvTelemetry != null) tvTelemetry.setVisibility(View.GONE);
            if (isAirMouseActive) {
                isAirMouseActive = false;
                if (sensorManager != null) sensorManager.unregisterListener(gyroListener);
                if (btnAirMouse != null) {
                    btnAirMouse.setText("AIR MOUSE");
                    applyButtonTheme(btnAirMouse, false);
                }
            }
        }
    }

    private GradientDrawable createBoxDrawable(int bgColor, int borderColor, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        float density = getResources().getDisplayMetrics().density;
        gd.setCornerRadius(radiusDp * density);
        gd.setStroke((int) (1 * density), borderColor);
        return gd;
    }

    private GradientDrawable createDotDrawable(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        return gd;
    }

    private void setupTabs() {
        View.OnClickListener tabListener = v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            switchTab(v.getId());
        };

        btnTabTouchpad.setOnClickListener(tabListener);
        btnTabMedia.setOnClickListener(tabListener);
        btnTabShortcuts.setOnClickListener(tabListener);
        btnTabApps.setOnClickListener(tabListener);
        btnTabKeyboard.setOnClickListener(tabListener);
    }

    private void switchTab(int id) {
        currentTabId = id;

        if (id != R.id.btnTabKeyboard && rootLayout != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(rootLayout.getWindowToken(), 0);
            }
        }

        viewTouchpad.setVisibility(id == R.id.btnTabTouchpad ? View.VISIBLE : View.GONE);
        viewMedia.setVisibility(id == R.id.btnTabMedia ? View.VISIBLE : View.GONE);
        viewShortcuts.setVisibility(id == R.id.btnTabShortcuts ? View.VISIBLE : View.GONE);
        viewApps.setVisibility(id == R.id.btnTabApps ? View.VISIBLE : View.GONE);
        viewKeyboard.setVisibility(id == R.id.btnTabKeyboard ? View.VISIBLE : View.GONE);

        applyTabStyle(btnTabTouchpad, id == R.id.btnTabTouchpad);
        applyTabStyle(btnTabMedia, id == R.id.btnTabMedia);
        applyTabStyle(btnTabShortcuts, id == R.id.btnTabShortcuts);
        applyTabStyle(btnTabApps, id == R.id.btnTabApps);
        applyTabStyle(btnTabKeyboard, id == R.id.btnTabKeyboard);
    }

    private void applyTabStyle(Button btn, boolean active) {
        if (btn == null) return;
        int activeBg = isDarkMode ? Color.parseColor("#FFFFFF") : Color.parseColor("#000000");
        int activeText = isDarkMode ? Color.parseColor("#000000") : Color.parseColor("#FFFFFF");

        int inactiveBg = isDarkMode ? Color.parseColor("#141416") : Color.parseColor("#E5E5EA");
        int inactiveText = isDarkMode ? Color.parseColor("#8A8A8E") : Color.parseColor("#636366");
        int inactiveBorder = isDarkMode ? Color.parseColor("#26262A") : Color.parseColor("#D1D1D6");

        if (active) {
            btn.setBackground(createBoxDrawable(activeBg, activeBg, 8));
            btn.setTextColor(activeText);
        } else {
            btn.setBackground(createBoxDrawable(inactiveBg, inactiveBorder, 8));
            btn.setTextColor(inactiveText);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchpad() {
        touchpadSurface.setOnTouchListener((v, event) -> {
            int pointerCount = event.getPointerCount();
            if (pointerCount > maxPointersInGesture) {
                maxPointersInGesture = pointerCount;
            }

            int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    startX = event.getX(0);
                    startY = event.getY(0);
                    lastX = startX;
                    lastY = startY;
                    mouseAccumX = 0f;
                    mouseAccumY = 0f;
                    touchStartTime = System.currentTimeMillis();
                    maxPointersInGesture = 1;
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() == 2) {
                        float dxPinch = event.getX(0) - event.getX(1);
                        float dyPinch = event.getY(0) - event.getY(1);
                        initialPinchDistance = (float) Math.hypot(dxPinch, dyPinch);
                        lastScrollY = (event.getY(0) + event.getY(1)) / 2f;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (pointerCount == 1) {
                        float curX = event.getX(0);
                        float curY = event.getY(0);
                        mouseAccumX += (curX - lastX) * TOUCH_SENSITIVITY;
                        mouseAccumY += (curY - lastY) * TOUCH_SENSITIVITY;
                        lastX = curX;
                        lastY = curY;

                        int moveX = (int) mouseAccumX;
                        int moveY = (int) mouseAccumY;
                        if (moveX != 0 || moveY != 0) {
                            mouseAccumX -= moveX;
                            mouseAccumY -= moveY;
                            sendMouseMove(moveX, moveY);
                        }
                    } else if (pointerCount == 2) {
                        float curPinchX = event.getX(0) - event.getX(1);
                        float curPinchY = event.getY(0) - event.getY(1);
                        float currentPinchDist = (float) Math.hypot(curPinchX, curPinchY);
                        float pinchDelta = currentPinchDist - initialPinchDistance;

                        if (Math.abs(pinchDelta) > 120f) {
                            if (pinchDelta > 0) {
                                sendAction("zoom_in");
                            } else {
                                sendAction("zoom_out");
                            }
                            initialPinchDistance = currentPinchDist;
                        } else {
                            float avgY = (event.getY(0) + event.getY(1)) / 2f;
                            float dyScroll = avgY - lastScrollY;
                            if (Math.abs(dyScroll) > 4f) {
                                sendScroll((int) (-dyScroll * 3.5f));
                                lastScrollY = avgY;
                            }
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    long duration = System.currentTimeMillis() - touchStartTime;
                    float endX = event.getX(0);
                    float endY = event.getY(0);
                    float totalDist = (float) Math.hypot(endX - startX, endY - startY);
                    float totalDx = endX - startX;
                    float totalDy = endY - startY;

                    if (maxPointersInGesture == 1) {
                        // 1-Finger Tap / Double-tap
                        if (duration < 240 && totalDist < 25f) {
                            long now = System.currentTimeMillis();
                            if (now - lastTapTime < 280) {
                                sendClick("d");
                                vibrate(30);
                            } else {
                                sendClick("l");
                                vibrate(15);
                            }
                            lastTapTime = now;
                        }
                    } else if (maxPointersInGesture == 2) {
                        // 2-Finger Tap -> Right Click
                        if (duration < 280 && totalDist < 35f) {
                            sendClick("r");
                            vibrate(25);
                        }
                    } else if (maxPointersInGesture == 3) {
                        // 3-Finger Tap: Search
                        if (duration < 280 && totalDist < 35f) {
                            sendAction("search");
                        } else if (Math.abs(totalDy) > Math.abs(totalDx) && Math.abs(totalDy) > 160f) {
                            if (totalDy < 0) {
                                sendAction("taskview"); // Swipe UP
                            } else {
                                sendAction("desktop");  // Swipe DOWN
                            }
                        } else if (Math.abs(totalDx) > 160f) {
                            if (totalDx < 0) {
                                sendAction("app_next"); // Swipe LEFT
                            } else {
                                sendAction("app_prev"); // Swipe RIGHT
                            }
                        }
                    } else if (maxPointersInGesture >= 4) {
                        // 4-Finger Tap: Action Center
                        if (duration < 280 && totalDist < 35f) {
                            sendAction("action_center");
                        } else if (Math.abs(totalDx) > Math.abs(totalDy) && Math.abs(totalDx) > 160f) {
                            if (totalDx < 0) {
                                sendAction("vdesk_right");
                            } else {
                                sendAction("vdesk_left");
                            }
                        } else if (Math.abs(totalDy) > 160f) {
                            if (totalDy < 0) {
                                sendAction("win_max");
                            } else {
                                sendAction("win_min");
                            }
                        }
                    }

                    maxPointersInGesture = 1;
                    break;

                case MotionEvent.ACTION_CANCEL:
                    maxPointersInGesture = 1;
                    break;
            }
            return true;
        });

        // Dedicated edge scroll bar
        scrollSurface.setOnTouchListener((v, event) -> {
            float y = event.getY();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastScrollY = y;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = y - lastScrollY;
                    if (Math.abs(dy) > 2) {
                        sendScroll((int) (-dy * 4.5f));
                        lastScrollY = y;
                    }
                    break;
            }
            return true;
        });

        btnLeftClick.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            sendClick("l");
        });

        btnRightClick.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            sendClick("r");
        });

        if (btnAirMouse != null) {
            btnAirMouse.setOnClickListener(v -> {
                vibrate(20);
                if (!btManager.isConnected()) {
                    Toast.makeText(this, "Connect to laptop first", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (gyroSensor == null) {
                    Toast.makeText(this, "Gyroscope sensor not available", Toast.LENGTH_SHORT).show();
                    return;
                }
                isAirMouseActive = !isAirMouseActive;
                if (isAirMouseActive) {
                    sensorManager.registerListener(gyroListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
                    btnAirMouse.setText("AIR MOUSE: ON");
                    btnAirMouse.setBackgroundResource(R.drawable.bg_button_active);
                    btnAirMouse.setTextColor(getColor(R.color.bg_black));
                } else {
                    sensorManager.unregisterListener(gyroListener);
                    btnAirMouse.setText("AIR MOUSE");
                    applyButtonTheme(btnAirMouse, false);
                }
            });
        }
    }

    private void setupMediaControls() {
        findViewById(R.id.btnVolUp).setOnClickListener(v -> sendKey("vol_up"));
        findViewById(R.id.btnVolDown).setOnClickListener(v -> sendKey("vol_down"));
        findViewById(R.id.btnMute).setOnClickListener(v -> sendKey("mute"));

        findViewById(R.id.btnPlayPause).setOnClickListener(v -> sendKey("play_pause"));
        findViewById(R.id.btnNext).setOnClickListener(v -> sendKey("next"));
        findViewById(R.id.btnPrev).setOnClickListener(v -> sendKey("prev"));

        findViewById(R.id.btnSlideNext).setOnClickListener(v -> sendKey("page_down"));
        findViewById(R.id.btnSlidePrev).setOnClickListener(v -> sendKey("page_up"));
        findViewById(R.id.btnF5).setOnClickListener(v -> sendKey("f5"));
        findViewById(R.id.btnEsc).setOnClickListener(v -> sendKey("esc"));
    }

    private void setupShortcuts() {
        // Biometric & Security
        View bUnlock = findViewById(R.id.btnUnlock);
        if (bUnlock != null) bUnlock.setOnClickListener(v -> triggerBiometricUnlock());

        View bSetPin = findViewById(R.id.btnSetPin);
        if (bSetPin != null) bSetPin.setOnClickListener(v -> promptSetPin());

        View bScreenOff = findViewById(R.id.btnScreenOff);
        if (bScreenOff != null) bScreenOff.setOnClickListener(v -> sendAction("screen_off"));

        View bLock = findViewById(R.id.btnLock);
        if (bLock != null) bLock.setOnClickListener(v -> sendAction("lock"));

        // Universal Clipboard
        View bCopyPc = findViewById(R.id.btnCopyFromPc);
        if (bCopyPc != null) bCopyPc.setOnClickListener(v -> copyFromPc());

        View bPastePc = findViewById(R.id.btnPasteToPc);
        if (bPastePc != null) bPastePc.setOnClickListener(v -> pasteToPc());

        View bCopy = findViewById(R.id.btnCopy);
        if (bCopy != null) bCopy.setOnClickListener(v -> sendAction("copy"));

        View bPaste = findViewById(R.id.btnPaste);
        if (bPaste != null) bPaste.setOnClickListener(v -> sendAction("paste"));

        // Productivity
        View bSnip = findViewById(R.id.btnSnip);
        if (bSnip != null) bSnip.setOnClickListener(v -> sendAction("snip"));

        View bMic = findViewById(R.id.btnMicMute);
        if (bMic != null) bMic.setOnClickListener(v -> sendAction("mic_mute"));

        View bTaskMgr = findViewById(R.id.btnTaskMgr);
        if (bTaskMgr != null) bTaskMgr.setOnClickListener(v -> sendAction("task_mgr"));

        View bSearch = findViewById(R.id.btnSearch);
        if (bSearch != null) bSearch.setOnClickListener(v -> sendAction("search"));

        // Windows Navigation
        View bDesk = findViewById(R.id.btnDesktop);
        if (bDesk != null) bDesk.setOnClickListener(v -> sendAction("desktop"));

        View bAltTab = findViewById(R.id.btnAltTab);
        if (bAltTab != null) bAltTab.setOnClickListener(v -> sendAction("alttab"));

        View bTasks = findViewById(R.id.btnTaskView);
        if (bTasks != null) bTasks.setOnClickListener(v -> sendAction("taskview"));

        View bAction = findViewById(R.id.btnActionCenter);
        if (bAction != null) bAction.setOnClickListener(v -> sendAction("action_center"));

        View bTerm = findViewById(R.id.btnLaunchTerminal);
        if (bTerm != null) bTerm.setOnClickListener(v -> sendAction("terminal"));

        View bExp = findViewById(R.id.btnLaunchExplorer);
        if (bExp != null) bExp.setOnClickListener(v -> sendAction("explorer"));

        View bDeskL = findViewById(R.id.btnVDeskLeft);
        if (bDeskL != null) bDeskL.setOnClickListener(v -> sendAction("vdesk_left"));

        View bDeskR = findViewById(R.id.btnVDeskRight);
        if (bDeskR != null) bDeskR.setOnClickListener(v -> sendAction("vdesk_right"));

        // Power Deck
        View bSleep = findViewById(R.id.btnSleep);
        if (bSleep != null) bSleep.setOnClickListener(v -> confirmPowerAction("Sleep Laptop", "sleep"));

        View bRestart = findViewById(R.id.btnRestart);
        if (bRestart != null) bRestart.setOnClickListener(v -> confirmPowerAction("Restart Laptop", "restart"));

        View bShutdown = findViewById(R.id.btnShutdown);
        if (bShutdown != null) bShutdown.setOnClickListener(v -> confirmPowerAction("Shutdown Laptop", "shutdown"));

        View bUndo = findViewById(R.id.btnUndo);
        if (bUndo != null) bUndo.setOnClickListener(v -> sendAction("undo"));
    }

    private void setupAppLauncher() {
        appsAdapter = new ArrayAdapter<String>(this, R.layout.item_app, filteredApps) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = convertView;
                if (v == null) {
                    v = getLayoutInflater().inflate(R.layout.item_app, parent, false);
                }
                String appName = getItem(position);
                TextView tv = v.findViewById(R.id.tvAppName);
                ImageView iv = v.findViewById(R.id.ivAppIcon);
                View card = v.findViewById(R.id.cardAppItem);

                tv.setText(appName != null ? appName : "");

                int cardBg = isDarkMode ? Color.parseColor("#141416") : Color.parseColor("#FFFFFF");
                int cardBorder = isDarkMode ? Color.parseColor("#26262A") : Color.parseColor("#D1D1D6");
                int textPri = isDarkMode ? Color.parseColor("#F5F5F7") : Color.parseColor("#1C1C1E");

                card.setBackground(createBoxDrawable(cardBg, cardBorder, 8));
                tv.setTextColor(textPri);

                if (appName != null && appIcons.containsKey(appName)) {
                    iv.setImageBitmap(appIcons.get(appName));
                    iv.setVisibility(View.VISIBLE);
                } else {
                    iv.setVisibility(View.GONE);
                    if (appName != null && !loadingApps.contains(appName)) {
                        loadingApps.add(appName);
                        final String targetApp = appName;
                        iconLoader.execute(() -> loadLogoAsync(targetApp));
                    }
                }

                return v;
            }
        };
        gvApps.setAdapter(appsAdapter);

        gvApps.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < filteredApps.size()) {
                String app = filteredApps.get(position);
                vibrate(30);
                launchApp(app);
                Toast.makeText(MainActivity.this, "Opening: " + app, Toast.LENGTH_SHORT).show();
            }
        });

        etAppSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnRefreshApps.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            requestAppList();
            Toast.makeText(this, "Refreshing apps list...", Toast.LENGTH_SHORT).show();
        });
    }

    private void filterApps(String query) {
        filteredApps.clear();
        String q = query.trim().toLowerCase();
        for (String app : allApps) {
            if (q.isEmpty() || app.toLowerCase().contains(q)) {
                filteredApps.add(app);
            }
        }
        if (appsAdapter != null) {
            appsAdapter.notifyDataSetChanged();
        }
        if (tvEmptyApps != null) {
            tvEmptyApps.setVisibility(filteredApps.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void launchApp(String appName) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "launch");
            obj.put("app", appName);
            btManager.send(obj);
        } catch (Exception ignored) {}
    }

    private void loadLogoAsync(String appName) {
        String slug = getAppSlug(appName);
        if (slug == null) return;
        try {
            URL url = new URL("https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons/png/" + slug + ".png");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                if (bmp != null) {
                    runOnUiThread(() -> {
                        appIcons.put(appName, bmp);
                        if (appsAdapter != null) {
                            appsAdapter.notifyDataSetChanged();
                        }
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    private String getAppSlug(String name) {
        String n = name.toLowerCase();
        if (n.contains("chrome")) return "chrome";
        if (n.contains("brave")) return "brave";
        if (n.contains("edge")) return "microsoft-edge";
        if (n.contains("firefox")) return "firefox";
        if (n.contains("code") || n.contains("visual studio")) return "visual-studio-code";
        if (n.contains("spotify")) return "spotify";
        if (n.contains("discord")) return "discord";
        if (n.contains("steam")) return "steam";
        if (n.contains("notepad")) return "notepad";
        if (n.contains("calc")) return "calculator";
        if (n.contains("explorer")) return "windows-explorer";
        if (n.contains("terminal")) return "windows-terminal";
        if (n.contains("cmd") || n.contains("command prompt")) return "terminal";
        if (n.contains("powershell")) return "powershell";
        if (n.contains("vlc")) return "vlc";
        if (n.contains("git")) return "git";
        if (n.contains("github")) return "github";
        if (n.contains("whatsapp")) return "whatsapp";
        if (n.contains("telegram")) return "telegram";
        if (n.contains("maltego")) return "maltego";
        if (n.contains("obs")) return "obs";
        if (n.contains("postman")) return "postman";
        if (n.contains("word")) return "microsoft-word";
        if (n.contains("excel")) return "microsoft-excel";
        if (n.contains("powerpoint")) return "microsoft-powerpoint";
        return null;
    }

    private void setupKeyboardControls() {
        btnOpenPhoneKeypad.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (etTextInput != null) {
                etTextInput.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etTextInput, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });

        btnSendText.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            String text = etTextInput.getText().toString();
            if (!text.isEmpty()) {
                sendText(text);
                etTextInput.setText("");
            } else {
                sendKey("enter");
            }
        });

        etTextInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (isUpdatingKeypadText) return;
                // Soft keyboard Backspace detection
                if (count > after) {
                    int deleteCount = count - after;
                    for (int i = 0; i < deleteCount; i++) {
                        sendKey("backspace");
                    }
                }
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isUpdatingKeypadText) return;
                if (count > 0) {
                    CharSequence typed = s.subSequence(start, start + count);
                    sendText(typed.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() > 60) {
                    isUpdatingKeypadText = true;
                    s.clear();
                    isUpdatingKeypadText = false;
                }
            }
        });

        etTextInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    sendKey("backspace");
                    return false;
                } else if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    sendKey("enter");
                    return true;
                }
            }
            return false;
        });

        etTextInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendKey("enter");
                return true;
            }
            return false;
        });

        findViewById(R.id.btnKeyEnter).setOnClickListener(v -> sendKey("enter"));
        findViewById(R.id.btnKeyBackspace).setOnClickListener(v -> sendKey("backspace"));
        findViewById(R.id.btnKeySpace).setOnClickListener(v -> sendKey("space"));
        findViewById(R.id.btnKeyEsc).setOnClickListener(v -> sendKey("esc"));

        findViewById(R.id.btnKeyTab).setOnClickListener(v -> sendKey("tab"));
        findViewById(R.id.btnKeyWin).setOnClickListener(v -> sendKey("lwin"));
        findViewById(R.id.btnKeyDel).setOnClickListener(v -> sendKey("delete"));

        findViewById(R.id.btnKeyUp).setOnClickListener(v -> sendKey("up"));
        findViewById(R.id.btnKeyDown).setOnClickListener(v -> sendKey("down"));
        findViewById(R.id.btnKeyLeft).setOnClickListener(v -> sendKey("left"));
        findViewById(R.id.btnKeyRight).setOnClickListener(v -> sendKey("right"));
    }

    private void sendMouseMove(int dx, int dy) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "m");
            obj.put("dx", dx);
            obj.put("dy", dy);
            btManager.send(obj);
        } catch (Exception ignored) {}
    }

    private void sendClick(String button) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "c");
            obj.put("b", button);
            btManager.send(obj);
        } catch (Exception ignored) {}
    }

    private void sendScroll(int dy) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "s");
            obj.put("dy", dy);
            btManager.send(obj);
        } catch (Exception ignored) {}
    }

    private void sendKey(String keyName) {
        vibrate(20);
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "k");
            obj.put("k", keyName);
            btManager.send(obj);
        } catch (Exception ignored) {}
    }

    private void sendAction(String actionName) {
        vibrate(30);
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "a");
            obj.put("a", actionName);
            btManager.send(obj);
        } catch (Exception ignored) {}
    }

    private void sendText(String text) {
        vibrate(15);
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "text");
            obj.put("text", text);
            btManager.send(obj);
        } catch (Exception ignored) {}
    }

    private void vibrate(int ms) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(ms);
        }
    }

    private void checkPermissionsAndLoadDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN},
                        PERMISSION_REQ_CODE);
                return;
            }
        }
        loadPairedDevices();
    }

    @SuppressLint("MissingPermission")
    private void loadPairedDevices() {
        bondedDevicesList.clear();
        bondedNamesList.clear();

        if (btAdapter == null) return;

        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
        int autoSelectIndex = -1;
        int i = 0;

        if (paired != null) {
            for (BluetoothDevice d : paired) {
                bondedDevicesList.add(d);
                String name = d.getName() != null ? d.getName() : "Unknown";
                String entry = name.toUpperCase() + " (" + d.getAddress() + ")";
                bondedNamesList.add(entry);

                if (name.toLowerCase().contains("shriram") || d.getAddress().equalsIgnoreCase("D8:B3:2F:41:49:62")) {
                    autoSelectIndex = i;
                }
                i++;
            }
        }

        if (bondedNamesList.isEmpty()) {
            bondedNamesList.add("NO PAIRED LAPTOP FOUND");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bondedNamesList);
        spDevices.setAdapter(adapter);

        if (autoSelectIndex != -1) {
            spDevices.setSelection(autoSelectIndex);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadPairedDevices();
        }
    }

    private final SensorEventListener gyroListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!isAirMouseActive || event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
            int orient = getResources().getConfiguration().orientation;
            float rawX, rawY;
            if (orient == Configuration.ORIENTATION_LANDSCAPE) {
                rawX = -event.values[2] * 35.0f;
                rawY = -event.values[1] * 35.0f;
            } else {
                rawX = -event.values[2] * 35.0f;
                rawY = -event.values[0] * 35.0f;
            }
            if (Math.abs(rawX) < 1.2f && Math.abs(rawY) < 1.2f) return;
            sendMouseMove(Math.round(rawX), Math.round(rawY));
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private void promptSetPin() {
        vibrate(20);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Enter Windows PIN");
        new AlertDialog.Builder(this)
                .setTitle("SET WINDOWS PIN")
                .setMessage("Your PIN is saved locally on your phone to unlock Windows securely via Biometrics.")
                .setView(input)
                .setPositiveButton("SAVE", (d, w) -> {
                    String pin = input.getText().toString().trim();
                    if (!pin.isEmpty()) {
                        getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("win_pin", pin).apply();
                        Toast.makeText(this, "PIN saved securely", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void triggerBiometricUnlock() {
        vibrate(25);
        String pin = getSharedPreferences("prefs", MODE_PRIVATE).getString("win_pin", "");
        if (pin.isEmpty()) {
            promptSetPin();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle("Unlock Windows PC")
                    .setSubtitle("Confirm fingerprint or face to send PIN")
                    .setNegativeButton("Cancel", getMainExecutor(), (d, w) -> {})
                    .build();

            prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    vibrate(40);
                    try {
                        JSONObject obj = new JSONObject();
                        obj.put("t", "unlock");
                        obj.put("pin", pin);
                        btManager.send(obj);
                        Toast.makeText(MainActivity.this, "Unlocked PC", Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    vibrate(100);
                    Toast.makeText(MainActivity.this, "Biometric failed", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            try {
                JSONObject obj = new JSONObject();
                obj.put("t", "unlock");
                obj.put("pin", pin);
                btManager.send(obj);
            } catch (Exception ignored) {}
        }
    }

    private void copyFromPc() {
        vibrate(25);
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "clip_get");
            btManager.send(obj);
            Toast.makeText(this, "Requesting PC Clipboard...", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private void pasteToPc() {
        vibrate(25);
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
            if (text != null && text.length() > 0) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("t", "clip_set");
                    obj.put("text", text.toString());
                    obj.put("paste", true);
                    btManager.send(obj);
                    Toast.makeText(this, "Pasted phone clipboard to PC", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            } else {
                Toast.makeText(this, "Phone clipboard is empty", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Phone clipboard is empty", Toast.LENGTH_SHORT).show();
        }
    }

    private void setAndroidClipboard(String text) {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            ClipData clip = ClipData.newPlainText("PC Clipboard", text);
            cb.setPrimaryClip(clip);
            Toast.makeText(this, "Copied from PC: " + (text.length() > 25 ? text.substring(0, 25) + "..." : text), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateTelemetryUI(int cpu, int ram, int bat, int ac) {
        if (tvTelemetry == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("CPU: ").append(cpu).append("% | ");
        sb.append("RAM: ").append(ram).append("% | ");
        sb.append("BAT: ").append(bat).append("%");
        if (ac == 1) {
            sb.append(" [AC]");
        }
        tvTelemetry.setText(sb.toString());
        tvTelemetry.setVisibility(View.VISIBLE);
    }

    private void confirmPowerAction(String title, String action) {
        vibrate(30);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Are you sure you want to " + title.toLowerCase() + " your PC?")
                .setPositiveButton("YES", (d, w) -> sendAction(action))
                .setNegativeButton("NO", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isAirMouseActive && sensorManager != null && gyroSensor != null) {
            sensorManager.registerListener(gyroListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(gyroListener);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) {
            sensorManager.unregisterListener(gyroListener);
        }
        try {
            iconLoader.shutdownNow();
        } catch (Exception ignored) {}
        btManager.disconnect();
    }
}