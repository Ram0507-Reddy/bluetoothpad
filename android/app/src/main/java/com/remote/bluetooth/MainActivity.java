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
    private TextView tvStatus;
    private Spinner spDevices;
    private Button btnConnect, btnThemeToggle;

    // Tabs
    private Button btnTabTouchpad, btnTabMedia, btnTabShortcuts, btnTabApps, btnTabKeyboard;
    private View viewTouchpad, viewMedia, viewShortcuts, viewApps, viewKeyboard;
    private int currentTabId = R.id.btnTabTouchpad;

    // Touchpad Views
    private View touchpadSurface, scrollSurface;
    private Button btnLeftClick, btnRightClick;
    private TextView tvTouchpadHint;

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
        tvTouchpadHint = findViewById(R.id.tvTouchpadHint);

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
            R.id.btnLock, R.id.btnDesktop, R.id.btnAltTab, R.id.btnTaskView,
            R.id.btnSearch, R.id.btnActionCenter, R.id.btnVDeskLeft, R.id.btnVDeskRight,
            R.id.btnWinMax, R.id.btnWinMin, R.id.btnLaunchTerminal, R.id.btnLaunchBrowser, R.id.btnLaunchExplorer,
            R.id.btnCopy, R.id.btnPaste, R.id.btnUndo, R.id.btnSelectAll,
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
        findViewById(R.id.btnLock).setOnClickListener(v -> sendAction("lock"));
        findViewById(R.id.btnDesktop).setOnClickListener(v -> sendAction("desktop"));
        findViewById(R.id.btnAltTab).setOnClickListener(v -> sendAction("alttab"));
        findViewById(R.id.btnTaskView).setOnClickListener(v -> sendAction("taskview"));

        findViewById(R.id.btnSearch).setOnClickListener(v -> sendAction("search"));
        findViewById(R.id.btnActionCenter).setOnClickListener(v -> sendAction("action_center"));
        findViewById(R.id.btnVDeskLeft).setOnClickListener(v -> sendAction("vdesk_left"));
        findViewById(R.id.btnVDeskRight).setOnClickListener(v -> sendAction("vdesk_right"));

        findViewById(R.id.btnWinMax).setOnClickListener(v -> sendAction("win_max"));
        findViewById(R.id.btnWinMin).setOnClickListener(v -> sendAction("win_min"));

        findViewById(R.id.btnLaunchTerminal).setOnClickListener(v -> sendAction("terminal"));
        findViewById(R.id.btnLaunchBrowser).setOnClickListener(v -> sendAction("browser"));
        findViewById(R.id.btnLaunchExplorer).setOnClickListener(v -> sendAction("explorer"));

        findViewById(R.id.btnCopy).setOnClickListener(v -> sendAction("copy"));
        findViewById(R.id.btnPaste).setOnClickListener(v -> sendAction("paste"));
        findViewById(R.id.btnUndo).setOnClickListener(v -> sendAction("undo"));
        findViewById(R.id.btnSelectAll).setOnClickListener(v -> sendAction("select_all"));
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            iconLoader.shutdownNow();
        } catch (Exception ignored) {}
        btManager.disconnect();
    }
}