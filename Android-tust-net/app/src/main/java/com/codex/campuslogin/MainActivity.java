package com.codex.campuslogin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String PORTAL_HOST = "http://10.10.102.50";
    private static final String PORTAL_LOGIN = "http://10.10.102.50:801/eportal/portal/login";
    private static final String PORTAL_LOGOUT = "http://10.10.102.50:801/eportal/portal/logout";
    private static final String PORTAL_ONLINE_LIST = "http://10.10.102.50:801/eportal/portal/online_list";
    private static final String[] USS_HOSTS = {"http://59.67.5.95", "http://uss.tust.edu.cn"};
    private static final String USS_HOST = USS_HOSTS[0];
    private static final String USS_LOGIN = USS_HOST + "/login/?302=LI";
    private static final String AC_IP = "10.10.102.49";
    private static final String AC_NAME = "";
    private static final String PREFS = "campus_login";
    private static final String[] OPERATOR_LABELS = {
            "\u6821\u56ed\u7f51",
            "\u4e2d\u56fd\u8054\u901a",
            "\u4e2d\u56fd\u79fb\u52a8",
            "\u4e2d\u56fd\u7535\u4fe1"
    };
    private static final String[] OPERATOR_SUFFIXES = {
            "",
            "@unicom",
            "@cmcc",
            "@telecom"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AutoCompleteTextView accountInput;
    private EditText passwordInput;
    private Spinner operatorSpinner;
    private TextView statusView;
    private TextView flowView;
    private Button loginButton;
    private Button logoutButton;
    private Button refreshButton;
    private ImageButton accountDropdownButton;
    private ImageButton passwordToggleButton;
    private SharedPreferences prefs;
    private boolean passwordVisible = false;
    private boolean accountDropdownOpen = false;
    private boolean accountDropdownButtonTouchActive = false;
    private boolean accountDropdownOpenBeforeButtonDown = false;
    private boolean suppressAccountDropdownDismiss = false;
    private String lastUssMessage = "";
    private final LinkedHashMap<String, AccountRecord> savedAccounts = new LinkedHashMap<>();
    private ArrayAdapter<String> accountAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        loadSettings();
        loginButton.setOnClickListener(v -> {
            saveSettings();
            login();
        });
        logoutButton.setOnClickListener(v -> logout());
        refreshButton.setOnClickListener(v -> refreshFlow());
        detectLoginState();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("\u9000\u51fa\u5e94\u7528\uff1f")
                .setMessage("\u786e\u5b9a\u8981\u9000\u51fa\u5929\u79d1\u6821\u7f51\u5417\uff1f")
                .setNegativeButton("\u53d6\u6d88", null)
                .setPositiveButton("\u9000\u51fa", (dialog, which) -> MainActivity.super.onBackPressed())
                .show();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFFF7FAFC);
        scrollView.setFocusableInTouchMode(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(22), dp(isTvLike() ? 64 : 54), dp(22), dp(34));
        scrollView.addView(root, new ScrollView.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("\u5929\u79d1\u6821\u7f51");
        title.setTextColor(0xFF0F172A);
        title.setTextSize(isTvLike() ? 36 : 32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView subtitle = new TextView(this);
        subtitle.setText("\u6821\u56ed\u7f51\u4e00\u952e\u8fde\u63a5");
        subtitle.setTextColor(0xFF64748B);
        subtitle.setTextSize(isTvLike() ? 20 : 16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, dp(20));
        root.addView(subtitle, fullWidth());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(roundRect(0xFFFFFFFF, 0xFFE2E8F0, dp(18), dp(1)));
        LinearLayout.LayoutParams panelParams = fullWidth();
        panelParams.bottomMargin = dp(16);
        root.addView(panel, panelParams);

        accountInput = addAccountInput(panel);
        passwordInput = addPasswordInput(panel);
        operatorSpinner = addOperatorSpinner(panel);

        loginButton = addButton(panel, "\u8054\u7f51", 0xFF0F766E, 0xFFFFFFFF);
        logoutButton = addButton(panel, "\u6ce8\u9500", 0xFFEFF6FF, 0xFF1D4ED8);
        logoutButton.setVisibility(View.GONE);
        refreshButton = addButton(panel, "\u5237\u65b0\u6d41\u91cf", 0xFFE0F2FE, 0xFF0369A1);
        refreshButton.setVisibility(View.GONE);

        LinearLayout resultPanel = new LinearLayout(this);
        resultPanel.setOrientation(LinearLayout.VERTICAL);
        resultPanel.setPadding(dp(18), dp(16), dp(18), dp(16));
        resultPanel.setBackground(roundRect(0xFFFFFFFF, 0xFFE2E8F0, dp(16), dp(1)));
        LinearLayout.LayoutParams resultParams = fullWidth();
        resultParams.topMargin = dp(16);
        root.addView(resultPanel, resultParams);

        statusView = addInfoText(resultPanel, "\u586b\u5199\u8d26\u53f7\u540e\u70b9\u51fb\u8054\u7f51", true);
        flowView = addInfoText(resultPanel, "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", false);
        setContentView(scrollView);
    }

    private AutoCompleteTextView addAccountInput(LinearLayout root) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp(14), 0, dp(8), 0);
        container.setMinimumHeight(dp(isTvLike() ? 64 : 54));
        container.setBackground(roundRect(0xFFF8FAFC, 0xFFCBD5E1, dp(12), dp(1)));

        AutoCompleteTextView input = new AutoCompleteTextView(this);
        input.setHint("\u5b66\u53f7");
        input.setSingleLine(true);
        input.setTextSize(isTvLike() ? 20 : 16);
        input.setTextColor(0xFF0F172A);
        input.setHintTextColor(0xFF94A3B8);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        input.setSelectAllOnFocus(false);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setThreshold(1000);
        input.setPadding(0, 0, dp(8), 0);
        input.setBackgroundColor(0x00000000);
        input.setDropDownBackgroundDrawable(roundRect(0xFFFFFFFF, 0xFFE2E8F0, dp(14), dp(1)));
        input.setDropDownVerticalOffset(dp(8));
        input.setDropDownHorizontalOffset(0);
        input.setOnFocusChangeListener((v, hasFocus) -> setAccountContainerFocusStyle(container, hasFocus));
        input.setOnClickListener(v -> showKeyboard(input));
        input.setOnKeyListener((v, keyCode, event) -> handleTextInputConfirmKey(input, keyCode, event));
        input.setOnTouchListener(null);
        input.setOnItemClickListener((parent, view, position, id) -> {
            fillSavedAccount(String.valueOf(parent.getItemAtPosition(position)));
            suppressAccountDropdownDismiss = false;
            accountDropdownOpen = false;
            input.dismissDropDown();
        });
        input.setOnDismissListener(() -> {
            if (suppressAccountDropdownDismiss) return;
            accountDropdownOpen = false;
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                passwordInput.requestFocus();
                return true;
            }
            return false;
        });
        container.addView(input, new LinearLayout.LayoutParams(0, -1, 1));

        accountDropdownButton = new ImageButton(this);
        accountDropdownButton.setImageDrawable(new DropdownArrowDrawable(0xFF0F766E, dp(18), dp(10)));
        accountDropdownButton.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        accountDropdownButton.setContentDescription("\u5c55\u5f00\u6216\u6536\u8d77\u5386\u53f2\u8d26\u53f7");
        accountDropdownButton.setMinimumWidth(dp(isTvLike() ? 64 : 48));
        accountDropdownButton.setMinimumHeight(dp(isTvLike() ? 54 : 44));
        accountDropdownButton.setBackground(roundRect(0xFFEFFCF9, 0xFFCCFBF1, dp(10), dp(1)));
        accountDropdownButton.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                accountDropdownButtonTouchActive = true;
                accountDropdownOpenBeforeButtonDown = accountDropdownOpen || accountInput.isPopupShowing();
                suppressAccountDropdownDismiss = accountDropdownOpenBeforeButtonDown;
            } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                accountDropdownButtonTouchActive = false;
                suppressAccountDropdownDismiss = false;
            }
            return false;
        });
        accountDropdownButton.setOnClickListener(v -> toggleAccountDropdown());
        accountDropdownButton.setOnFocusChangeListener((v, hasFocus) -> setAccountDropdownButtonFocusStyle(hasFocus));
        container.addView(accountDropdownButton, new LinearLayout.LayoutParams(dp(isTvLike() ? 64 : 50), dp(isTvLike() ? 54 : 44)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        root.addView(container, params);
        return input;
    }

    private EditText addPasswordInput(LinearLayout root) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp(14), 0, dp(8), 0);
        container.setMinimumHeight(dp(isTvLike() ? 64 : 54));
        container.setBackground(roundRect(0xFFF8FAFC, 0xFFCBD5E1, dp(12), dp(1)));

        EditText input = new EditText(this);
        input.setHint("\u5bc6\u7801");
        input.setSingleLine(true);
        input.setTextSize(isTvLike() ? 20 : 16);
        input.setTextColor(0xFF0F172A);
        input.setHintTextColor(0xFF94A3B8);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        input.setSelectAllOnFocus(false);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setPadding(0, 0, dp(8), 0);
        input.setBackgroundColor(0x00000000);
        input.setOnFocusChangeListener((v, hasFocus) -> setPasswordContainerFocusStyle(container, hasFocus));
        input.setOnClickListener(v -> showKeyboard(input));
        input.setOnKeyListener((v, keyCode, event) -> handleTextInputConfirmKey(input, keyCode, event));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                finishTextInput();
                return true;
            }
            return false;
        });
        container.addView(input, new LinearLayout.LayoutParams(0, -1, 1));

        passwordToggleButton = new ImageButton(this);
        passwordToggleButton.setImageResource(R.drawable.ic_eye_off);
        passwordToggleButton.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        passwordToggleButton.setContentDescription("\u663e\u793a\u6216\u9690\u85cf\u5bc6\u7801");
        passwordToggleButton.setMinimumWidth(dp(isTvLike() ? 64 : 48));
        passwordToggleButton.setMinimumHeight(dp(isTvLike() ? 54 : 44));
        passwordToggleButton.setBackground(roundRect(0xFFEFFCF9, 0xFFCCFBF1, dp(10), dp(1)));
        passwordToggleButton.setOnClickListener(v -> togglePasswordVisibility());
        passwordToggleButton.setOnFocusChangeListener((v, hasFocus) -> setPasswordToggleFocusStyle(hasFocus));
        container.addView(passwordToggleButton, new LinearLayout.LayoutParams(dp(isTvLike() ? 64 : 50), dp(isTvLike() ? 54 : 44)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        root.addView(container, params);
        return input;
    }

    private Spinner addOperatorSpinner(LinearLayout root) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, OPERATOR_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setFocusable(true);
        spinner.setPadding(dp(10), 0, dp(10), 0);
        spinner.setMinimumHeight(dp(isTvLike() ? 64 : 54));
        spinner.setBackground(roundRect(0xFFF8FAFC, 0xFFCBD5E1, dp(12), dp(1)));
        spinner.setOnFocusChangeListener((v, hasFocus) -> v.setBackground(roundRect(hasFocus ? 0xFFEFF6FF : 0xFFF8FAFC, hasFocus ? 0xFF1D4ED8 : 0xFFCBD5E1, dp(12), hasFocus ? dp(3) : dp(1))));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        root.addView(spinner, params);
        return spinner;
    }

    private Button addButton(LinearLayout root, String text, int bgColor, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(isTvLike() ? 22 : 18);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            button.setElevation(0);
            button.setStateListAnimator(null);
        }
        button.setBackground(roundRect(bgColor, bgColor, dp(14), 0));
        button.setOnFocusChangeListener((v, hasFocus) -> {
            int fillColor = hasFocus ? 0xFF1D4ED8 : bgColor;
            button.setTextColor(hasFocus ? 0xFFFFFFFF : textColor);
            button.setTextSize(hasFocus ? (isTvLike() ? 23 : 19) : (isTvLike() ? 22 : 18));
            button.setScaleX(1f);
            button.setScaleY(1f);
            button.setBackground(roundRect(fillColor, fillColor, dp(14), 0));
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(isTvLike() ? 68 : 56));
        params.topMargin = dp(10);
        root.addView(button, params);
        return button;
    }

    private TextView addInfoText(LinearLayout root, String text, boolean strong) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(strong ? 0xFF0F172A : 0xFF475569);
        view.setTextSize(isTvLike() ? (strong ? 21 : 19) : (strong ? 17 : 15));
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(2, 1.08f);
        if (strong) view.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private void loadSettings() {
        savedAccounts.clear();
        String raw = prefs.getString("accounts_json", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String account = item.optString("account", "").trim();
                if (TextUtils.isEmpty(account)) continue;
                savedAccounts.put(account, new AccountRecord(
                        item.optString("password", ""),
                        item.optString("operator", "0")
                ));
            }
        } catch (Exception ignored) {
        }

        String legacyAccount = prefs.getString("account", "").trim();
        if (!TextUtils.isEmpty(legacyAccount) && !savedAccounts.containsKey(legacyAccount)) {
            savedAccounts.put(legacyAccount, new AccountRecord(
                    prefs.getString("password", ""),
                    prefs.getString("operator", "0")
            ));
        }
        refreshAccountAdapter();
        String current = prefs.getString("current_account", legacyAccount);
        accountInput.setText(current, false);
        fillSavedAccount(current);
    }

    private void saveSettings() {
        rememberCurrentAccount();
        prefs.edit()
                .putString("current_account", textOf(accountInput))
                .putString("account", textOf(accountInput))
                .putString("password", textOf(passwordInput))
                .putString("operator", selectedOperator())
                .putString("accounts_json", serializeAccounts())
                .apply();
    }

    private void rememberCurrentAccount() {
        String account = textOf(accountInput);
        if (TextUtils.isEmpty(account)) return;
        if (savedAccounts.containsKey(account)) savedAccounts.remove(account);
        savedAccounts.put(account, new AccountRecord(textOf(passwordInput), selectedOperator()));
        refreshAccountAdapter();
    }

    private String serializeAccounts() {
        JSONArray array = new JSONArray();
        try {
            List<Map.Entry<String, AccountRecord>> entries = new ArrayList<>(savedAccounts.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                Map.Entry<String, AccountRecord> entry = entries.get(i);
                JSONObject item = new JSONObject();
                item.put("account", entry.getKey());
                item.put("password", entry.getValue().password);
                item.put("operator", entry.getValue().operator);
                array.put(item);
            }
        } catch (Exception ignored) {
        }
        return array.toString();
    }

    private void refreshAccountAdapter() {
        List<String> accounts = new ArrayList<>(savedAccounts.keySet());
        java.util.Collections.reverse(accounts);
        accountAdapter = new AccountDropdownAdapter(accounts);
        accountInput.setAdapter(accountAdapter);
    }

    private void toggleAccountDropdown() {
        if (accountAdapter == null || accountAdapter.getCount() == 0) return;
        boolean wasOpen = accountDropdownButtonTouchActive
                ? accountDropdownOpenBeforeButtonDown
                : (accountDropdownOpen || accountInput.isPopupShowing());
        setAccountDropdownVisible(!wasOpen);
        accountDropdownButtonTouchActive = false;
        accountDropdownOpenBeforeButtonDown = false;
        suppressAccountDropdownDismiss = false;
    }

    private void setAccountDropdownVisible(boolean visible) {
        if (accountAdapter == null || accountAdapter.getCount() == 0) return;
        if (visible) {
            suppressAccountDropdownDismiss = false;
            accountInput.requestFocus();
            accountInput.showDropDown();
            accountDropdownOpen = true;
        } else {
            accountInput.dismissDropDown();
            accountDropdownOpen = false;
        }
    }

    private void fillSavedAccount(String account) {
        if (TextUtils.isEmpty(account)) return;
        AccountRecord record = savedAccounts.get(account.trim());
        if (record == null) return;
        passwordInput.setText(record.password);
        operatorSpinner.setSelection(safeOperatorIndex(record.operator));
    }

    private void login() {
        String account = textOf(accountInput);
        String password = textOf(passwordInput);
        String operator = selectedOperator();
        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(password)) {
            showResult("\u8bf7\u5148\u586b\u5199\u5b66\u53f7\u548c\u5bc6\u7801", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", false, false);
            return;
        }

        setActionBusy(loginButton, "\u8054\u7f51\u4e2d...", "\u6b63\u5728\u8fde\u63a5\u6821\u56ed\u7f51...");
        new Thread(() -> {
            try {
                bindToWifiIfAvailable();
                if (!isOnCampusWifi()) {
                    showResult("\u672a\u8fde\u63a5\u5230\u6b63\u786e\u7684\u6821\u56ed\u7f51 Wi-Fi", "\u6d41\u91cf\uff1a\u672a\u77e5", false, false, true);
                    return;
                }
                String ip = getLocalIpv4();
                if (TextUtils.isEmpty(ip)) {
                    showResult("\u672a\u83b7\u53d6\u5230\u8bbe\u5907 IP\uff0c\u8bf7\u5148\u8fde\u63a5\u6821\u56ed\u7f51 Wi-Fi", "\u6d41\u91cf\uff1a\u672a\u77e5", false, false);
                    return;
                }

                String portalPage = "";
                try {
                    portalPage = httpGet(buildPortalUrl(ip), 8000);
                    if (containsAny(portalPage, "\u6ce8\u9500\u9875", "olflow=")) {
                        FlowInfo flow = parseFlow(portalPage);
                        showResult("\u5df2\u767b\u5f55\uff1a\u8bbe\u5907 IP " + ip, formatFlow(flow, ip), false, true);
                        return;
                    }
                } catch (Exception ignored) {
                    // Some campus networks block the portal landing page but still accept the login API.
                }

                String loginResult = httpGet(buildLoginUrl(ip, account, password, operator), 10000);
                if (containsAny(loginResult, "\u534f\u8bae\u8ba4\u8bc1\u6210\u529f", "\"result\":\"1\"", "\u8ba4\u8bc1\u6210\u529f")) {
                    String flowSource = loginResult;
                    try {
                        flowSource = flowSource + "\n" + httpGet(buildOnlineListUrl(ip), 8000);
                    } catch (Exception ignored) {
                    }
                    try {
                        flowSource = flowSource + "\n" + queryUssFlow(account, password);
                    } catch (Exception ex) {
                        lastUssMessage = "USS\u67e5\u8be2\u5931\u8d25\uff1a" + ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                    }
                    FlowInfo flow = parseFlow(flowSource);
                    showResult("\u767b\u5f55\u6210\u529f\uff1a\u8bbe\u5907 IP " + ip, formatFlow(flow, ip), false, true);
                } else if (isAlreadyOnline(loginResult)) {
                    String flowSource = loginResult;
                    try {
                        String page = httpGet(buildPortalUrl(ip), 8000);
                        if (!TextUtils.isEmpty(page)) flowSource = page;
                    } catch (Exception ignored) {
                    }
                    try {
                        flowSource = flowSource + "\n" + httpGet(buildOnlineListUrl(ip), 8000);
                    } catch (Exception ignored) {
                    }
                    try {
                        flowSource = flowSource + "\n" + queryUssFlow(account, password);
                    } catch (Exception ex) {
                        lastUssMessage = "USS\u67e5\u8be2\u5931\u8d25\uff1a" + ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                    }
                    FlowInfo flow = parseFlow(flowSource);
                    showResult("\u8be5\u8d26\u53f7\u5df2\u767b\u5f55\uff1a\u8bbe\u5907 IP " + ip, formatFlow(flow, ip), false, true);
                } else {
                    String error = classifyLoginError(loginResult);
                    showResult(error, "\u6d41\u91cf\uff1a\u672a\u77e5", false, false, true);
                }
            } catch (Exception ex) {
                showResult("\u8fde\u63a5\u5931\u8d25\uff1a" + ex.getClass().getSimpleName() + ": " + ex.getMessage(), "\u6d41\u91cf\uff1a\u672a\u77e5", false, false);
            }
        }).start();
    }

    private void detectLoginState() {
        String account = textOf(accountInput);
        String password = textOf(passwordInput);
        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(password)) {
            showResult("\u672a\u767b\u5f55\uff1a\u8bf7\u5148\u586b\u5199\u5b66\u53f7\u548c\u5bc6\u7801", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", false, false, false);
            return;
        }
        setBusy(true, "\u6b63\u5728\u68c0\u6d4b\u767b\u5f55\u72b6\u6001...");
        new Thread(() -> {
            try {
                bindToWifiIfAvailable();
                if (!isOnCampusWifi()) {
                    showResult("\u672a\u8fde\u63a5\u5230\u6b63\u786e\u7684\u6821\u56ed\u7f51 Wi-Fi", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", false, false, true);
                    return;
                }
                String ip = getLocalIpv4();
                if (TextUtils.isEmpty(ip)) {
                    showResult("\u8bf7\u5148\u8fde\u63a5\u6821\u56ed\u7f51 Wi-Fi", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", false, false, false);
                    return;
                }
                String flowSource;
                try {
                    flowSource = queryUssFlow(account, password);
                } catch (Exception ex) {
                    showResult("\u672a\u767b\u5f55\uff1a\u65e0\u6cd5\u8bbf\u95ee USS \u67e5\u8be2\u9875", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", false, false, false);
                    return;
                }
                FlowInfo flow = parseFlow(flowSource);
                List<String> onlineIps = extractOnlineIps(flowSource);
                if (onlineIps.contains(ip)) {
                    showResult("\u5f53\u524d\u8d26\u53f7\u5df2\u8fde\u63a5\uff1a\u8bbe\u5907 IP " + ip, formatFlow(flow, ip), false, true, true);
                } else if (!onlineIps.isEmpty()) {
                    showResult("\u672a\u767b\u5f55\uff1a\u672c\u673a IP " + ip + "\uff0c\u8be5\u8d26\u53f7\u6709\u5176\u4ed6\u8bbe\u5907\u5728\u7ebf", formatFlow(flow, ip), false, false, true);
                } else {
                    showResult("\u672a\u767b\u5f55\uff1a\u8bbe\u5907 IP " + ip, formatFlow(flow, ip), false, false, false);
                }
            } catch (Exception ex) {
                showResult("\u672a\u68c0\u6d4b\u5230\u5df2\u767b\u5f55\u72b6\u6001", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", false, false, false);
            }
        }).start();
    }

    private void refreshFlow() {
        String account = textOf(accountInput);
        String password = textOf(passwordInput);
        setActionBusy(refreshButton, "\u5237\u65b0\u4e2d...", "\u6b63\u5728\u5237\u65b0\u6d41\u91cf...");
        new Thread(() -> {
            try {
                bindToWifiIfAvailable();
                if (!isOnCampusWifi()) {
                    showResult("\u672a\u8fde\u63a5\u5230\u6b63\u786e\u7684\u6821\u56ed\u7f51 Wi-Fi", flowView.getText().toString(), false, false, true);
                    return;
                }
                String ip = getLocalIpv4();
                String flowSource = "";
                try {
                    flowSource += httpGet(buildPortalUrl(ip), 8000);
                } catch (Exception ignored) {
                }
                try {
                    flowSource += "\n" + httpGet(buildOnlineListUrl(ip), 8000);
                } catch (Exception ignored) {
                }
                try {
                    flowSource += "\n" + queryUssFlow(account, password);
                } catch (Exception ex) {
                    lastUssMessage = "USS\u67e5\u8be2\u5931\u8d25\uff1a" + ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                }
                FlowInfo flow = parseFlow(flowSource);
                if (isCurrentAccount(flowSource, account)) {
                    showResult("\u6d41\u91cf\u5df2\u5237\u65b0", formatFlow(flow, ip), false, true, true);
                } else {
                    showResult("\u5df2\u8fde\u63a5\u6821\u56ed\u7f51\uff0c\u4f46\u4e0d\u662f\u5f53\u524d\u5b66\u53f7", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", false, false, true);
                }
            } catch (Exception ex) {
                showResult("\u5237\u65b0\u5931\u8d25\uff1a" + ex.getClass().getSimpleName() + ": " + safeMessage(ex), flowView.getText().toString(), false, true, false);
            }
        }).start();
    }

    private void logout() {
        setActionBusy(logoutButton, "\u6ce8\u9500\u4e2d...", "\u6b63\u5728\u6ce8\u9500...");
        new Thread(() -> {
            try {
                bindToWifiIfAvailable();
                if (!isOnCampusWifi()) {
                    showResult("\u672a\u8fde\u63a5\u5230\u6b63\u786e\u7684\u6821\u56ed\u7f51 Wi-Fi", flowView.getText().toString(), false, true, true);
                    return;
                }
                String ip = getLocalIpv4();
                String result = httpGet(buildLogoutUrl(ip), 10000);
                if (containsAny(result, "\u6ce8\u9500\u6210\u529f", "logout", "\"result\":\"1\"", "\u4e0a\u7f51\u767b\u5f55\u9875")) {
                    showResult("\u5df2\u6ce8\u9500", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", false, false, true);
                } else {
                    showResult("\u6ce8\u9500\u8bf7\u6c42\u5df2\u53d1\u9001\uff1a" + trimForDisplay(result), "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", false, false, true);
                }
            } catch (Exception ex) {
                showResult("\u6ce8\u9500\u5931\u8d25\uff1a" + ex.getClass().getSimpleName() + ": " + ex.getMessage(), flowView.getText().toString(), false, true);
            }
        }).start();
    }

    private String buildPortalUrl(String ip) throws Exception {
        return PORTAL_HOST
                + "/a79.htm?wlanuserip=" + enc(ip)
                + "&wlanacname=" + enc(AC_NAME)
                + "&wlanacip=" + enc(AC_IP);
    }

    private String buildLoginUrl(String ip, String account, String password, String operator) throws Exception {
        String query = "callback=dr1239"
                + "&login_method=1"
                + "&user_account=" + enc(",0," + account + operatorSuffix(operator))
                + "&user_password=" + enc(password)
                + "&wlan_user_ip=" + enc(ip)
                + "&wlan_user_ipv6=" + enc(getLocalIpv6())
                + "&wlan_user_mac=000000000000"
                + "&wlan_ac_ip=" + enc(AC_IP)
                + "&wlan_ac_name=" + enc(AC_NAME)
                + "&jsVersion=4.1.3"
                + "&terminal_type=1"
                + "&lang=zh-cn"
                + "&v=1873"
                + "&lang=zh";
        return PORTAL_LOGIN + "?" + query;
    }

    private String buildLogoutUrl(String ip) throws Exception {
        String query = "callback=dr1002"
                + "&login_method=1"
                + "&user_account=drcom"
                + "&user_password=123"
                + "&wlan_user_ip=" + enc(ip == null ? "" : ip)
                + "&wlan_user_ipv6="
                + "&wlan_vlan_id=0"
                + "&wlan_user_mac=000000000000"
                + "&wlan_ac_ip=" + enc(AC_IP)
                + "&wlan_ac_name=" + enc(AC_NAME)
                + "&jsVersion=4.1.3"
                + "&v=1873"
                + "&lang=zh";
        return PORTAL_LOGOUT + "?" + query;
    }

    private String buildOnlineListUrl(String ip) throws Exception {
        String ipNumber = String.valueOf(ipToUnsignedInt(ip));
        String query = "callback=dr1019"
                + "&user_account="
                + "&user_password=123"
                + "&wlan_user_mac=000000000000"
                + "&wlan_user_ip=" + enc(ipNumber)
                + "&curr_user_ip=" + enc(ipNumber)
                + "&jsVersion=4.1.3"
                + "&v=3344"
                + "&lang=zh";
        return PORTAL_ONLINE_LIST + "?" + query;
    }

    private String queryUssFlow(String account, String password) throws Exception {
        lastUssMessage = "";
        Exception lastError = null;
        for (String host : USS_HOSTS) {
            try {
                return queryUssFlowFromHost(host, account, password);
            } catch (Exception ex) {
                lastError = ex;
            }
        }
        throw lastError == null ? new IllegalStateException("USS dashboard has no flow") : lastError;
    }

    private String queryUssFlowFromHost(String host, String account, String password) throws Exception {
        String loginPage = httpGet(host + "/login/?302=LI", 10000);
        String checkCode = firstRegex(loginPage, "name=\"checkcode\"\\s+value=\"([^\"]*)\"");
        String action = firstRegex(loginPage, "<form\\s+action=\"([^\"]*login/verify[^\"]*)\"");
        if (TextUtils.isEmpty(action)) action = "/login/verify";
        String verifyUrl = absoluteUssUrl(action, host);
        if (TextUtils.isEmpty(checkCode)) {
            throw new IllegalStateException("checkcode not found");
        }
        httpGet(host + "/login/randomCode?t=" + System.currentTimeMillis(), 10000);

        String body = "foo="
                + "&bar="
                + "&checkcode=" + enc(checkCode)
                + "&account=" + enc(account)
                + "&password=" + enc(password)
                + "&code=";
        String dashboard = httpPostForm(verifyUrl, body, 12000);
        if (!dashboard.contains("leftFlow") && !dashboard.contains("useFlow")) {
            dashboard = httpGet(host + "/dashboard", 10000);
        }
        if (!dashboard.contains("leftFlow") && !dashboard.contains("useFlow")) {
            throw new IllegalStateException("dashboard has no flow");
        }
        try {
            dashboard += "\n" + httpGet(ussOnlineListUrl(host), 8000);
        } catch (Exception ignored) {
        }
        lastUssMessage = "USS\u6d41\u91cf\u67e5\u8be2\u6210\u529f";
        return dashboard;
    }

    private static String ussOnlineListUrl(String host) {
        long stamp = System.currentTimeMillis();
        return host + "/dashboard/getOnlineList?t=" + Math.random() + "&order=asc&_=" + stamp;
    }

    private static String absoluteUssUrl(String action, String host) {
        if (action.startsWith("http://") || action.startsWith("https://")) return action;
        if (!action.startsWith("/")) action = "/" + action;
        return host + action;
    }

    private static String firstRegex(String source, String pattern) {
        Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(source == null ? "" : source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private FlowInfo parseFlow(String source) {
        long remainingMb = firstPositive(
                extractLabeledHtmlMb(source, "\u53ef\u7528\u6d41\u91cf"),
                extractKbAsMb(source, "olflow"),
                extractKbAsMb(source, "remainflow"),
                extractKbAsMb(source, "leftflow"),
                extractJsonMb(source, "leftFlow"),
                extractJsonMb(source, "left_flow"),
                extractJsonMb(source, "available_flow")
        );
        long usedMb = firstPositive(
                extractLabeledHtmlMb(source, "\u5df2\u7528\u6d41\u91cf"),
                extractKbAsMb(source, "flow"),
                extractKbAsMb(source, "usedflow"),
                extractKbAsMb(source, "useflow"),
                extractJsonMb(source, "useFlow"),
                extractJsonMb(source, "internetDownFlow"),
                extractJsonMb(source, "use_flow"),
                extractJsonMb(source, "use_flow_only")
        );
        long baseMb = firstPositive(
                extractLabeledHtmlMb(source, "\u57fa\u7840\u8d60\u9001\u6d41\u91cf"),
                extractJsonMb(source, "flowStart")
        );
        long totalMb = firstPositive(
                extractKbAsMb(source, "allflow"),
                extractKbAsMb(source, "totalflow"),
                extractKbAsMb(source, "sumflow"),
                extractKbAsMb(source, "monthflow"),
                extractJsonMb(source, "totalFlow")
        );
        if (totalMb < 0 && remainingMb >= 0 && usedMb >= 0) {
            totalMb = remainingMb + usedMb;
        }
        if (usedMb < 0 && totalMb > 0 && remainingMb >= 0) {
            usedMb = Math.max(0, totalMb - remainingMb);
        }
        List<String> onlineIps = extractOnlineIps(source);
        long onlineCount = onlineIps.isEmpty() ? countOnlineListItems(source) : onlineIps.size();
        return new FlowInfo(totalMb, remainingMb, usedMb, baseMb, onlineCount, onlineIps);
    }

    private String formatFlow(FlowInfo flow, String localIp) {
        String remaining = flow.remainingMb >= 0 ? flow.remainingMb + " MB" : "\u672a\u77e5";
        String used = flow.usedMb >= 0 ? flow.usedMb + " MB" : "\u672a\u77e5";
        String base = flow.baseMb >= 0 ? flow.baseMb + " MB" : "\u672a\u77e5";
        String online = formatOnlineStatus(flow, localIp);
        return "\u5269\u4f59\u6d41\u91cf\uff1a" + remaining
                + "\n\u5df2\u7528\u6d41\u91cf\uff1a" + used
                + "\n\u57fa\u7840\u8d60\u9001\u6d41\u91cf\uff1a" + base
                + "\n\u5f53\u524d\u5728\u7ebf\uff1a" + online;
    }

    private String formatOnlineStatus(FlowInfo flow, String localIp) {
        String count = flow.onlineCount >= 0 ? flow.onlineCount + " \u53f0" : "\u672a\u77e5";
        List<String> others = new ArrayList<>();
        for (String ip : flow.onlineIps) {
            if (!isValidOnlineIp(ip)) continue;
            if (!TextUtils.isEmpty(localIp) && localIp.equals(ip)) continue;
            if (!others.contains(ip)) others.add(ip);
        }
        if (others.isEmpty()) return count;
        return count + "\uff0c\u5176\u4ed6 IP\uff1a" + TextUtils.join("\u3001", others);
    }

    private static long extractKbAsMb(String source, String key) {
        Matcher matcher = Pattern.compile(key + "=(\\d+);").matcher(source == null ? "" : source);
        if (!matcher.find()) return -1;
        return Long.parseLong(matcher.group(1)) / 1024;
    }

    private static long extractJsonMb(String source, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)\"?").matcher(source == null ? "" : source);
        if (!matcher.find()) return -1;
        double value = Double.parseDouble(matcher.group(1));
        if (value < 0) return -1;
        return Math.round(value);
    }

    private static long extractLabeledHtmlMb(String source, String label) {
        if (source == null) return -1;
        Matcher pair = Pattern.compile("<dt[^>]*>(.*?)</dt>\\s*<dd[^>]*>(.*?)</dd>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(source);
        while (pair.find()) {
            String value = pair.group(1).replaceAll("<[^>]+>", " ");
            String name = pair.group(2).replaceAll("<[^>]+>", "").trim();
            if (label.equals(name)) {
                Matcher number = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(value);
                if (number.find()) return Math.round(Double.parseDouble(number.group(1)));
            }
        }
        int pos = source.indexOf(label);
        if (pos >= 0) {
            String before = source.substring(Math.max(0, pos - 300), pos);
            Matcher number = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(before);
            long result = -1;
            while (number.find()) result = Math.round(Double.parseDouble(number.group(1)));
            return result;
        }
        return -1;
    }

    private static List<String> extractOnlineIps(String source) {
        List<String> ips = new ArrayList<>();
        if (source == null) return ips;
        Matcher matcher = Pattern.compile("\"(?:ip|loginIp|login_ip|sourceIp|source_ip|userIp|user_ip)\"\\s*:\\s*\"([^\"]+)\"").matcher(source);
        while (matcher.find()) {
            String ip = matcher.group(1).trim();
            if (isValidOnlineIp(ip) && !ips.contains(ip)) ips.add(ip);
        }
        return ips;
    }

    private static boolean isValidOnlineIp(String ip) {
        if (TextUtils.isEmpty(ip) || "0.0.0.0".equals(ip) || "255.255.255.255".equals(ip)) return false;
        return Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matcher(ip).matches();
    }

    private static long countOnlineListItems(String source) {
        List<String> ips = extractOnlineIps(source);
        if (!ips.isEmpty()) return ips.size();
        if (source != null && source.contains("\u5728\u7ebf") && source.contains("\u6570\u636e\u4e3a\u7a7a")) return 0;
        return -1;
    }

    private static long ipToUnsignedInt(String ip) {
        if (TextUtils.isEmpty(ip)) return 0;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return 0;
        long result = 0;
        for (String part : parts) {
            result = (result << 8) + Integer.parseInt(part);
        }
        return result;
    }

    private static long firstPositive(long... values) {
        for (long value : values) {
            if (value >= 0) return value;
        }
        return -1;
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CampusNetLogin/1.0");
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPostForm(String url, String body, int timeoutMs) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(true);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CampusNetLogin/1.0");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Referer", USS_LOGIN);
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        conn.getOutputStream().write(bytes);

        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String getLocalIpv4() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface item = interfaces.nextElement();
            if (!item.isUp() || item.isLoopback()) continue;
            String name = item.getName().toLowerCase(Locale.ROOT);
            Enumeration<InetAddress> addresses = item.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    if (name.contains("wlan") || name.contains("wifi")) return address.getHostAddress();
                }
            }
        }

        interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface item = interfaces.nextElement();
            if (!item.isUp() || item.isLoopback()) continue;
            Enumeration<InetAddress> addresses = item.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        return "";
    }

    private static String getLocalIpv6() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface item = interfaces.nextElement();
            if (!item.isUp() || item.isLoopback()) continue;
            String name = item.getName().toLowerCase(Locale.ROOT);
            Enumeration<InetAddress> addresses = item.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet6Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    if (name.contains("wlan") || name.contains("wifi")) return stripIpv6Scope(address.getHostAddress());
                }
            }
        }

        interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface item = interfaces.nextElement();
            if (!item.isUp() || item.isLoopback()) continue;
            Enumeration<InetAddress> addresses = item.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet6Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    return stripIpv6Scope(address.getHostAddress());
                }
            }
        }
        return "";
    }

    private static String stripIpv6Scope(String ip) {
        if (ip == null) return "";
        int scopeIndex = ip.indexOf('%');
        return scopeIndex >= 0 ? ip.substring(0, scopeIndex) : ip;
    }

    private void bindToWifiIfAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        Network[] networks = connectivityManager.getAllNetworks();
        for (Network network : networks) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                connectivityManager.bindProcessToNetwork(network);
                return;
            }
        }
    }

    private boolean isOnCampusWifi() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return false;
        boolean hasWifi = false;
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                hasWifi = true;
                break;
            }
        }
        if (!hasWifi) return false;
        try {
            httpGet(buildPortalUrl(getLocalIpv4()), 2500);
            return true;
        } catch (Exception ignored) {
        }
        try {
            httpGet(PORTAL_HOST, 2500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void setBusy(boolean busy, String detail) {
        mainHandler.post(() -> {
            loginButton.setEnabled(!busy);
            logoutButton.setEnabled(!busy);
            refreshButton.setEnabled(!busy);
            statusView.setText(detail);
        });
    }

    private void setActionBusy(Button activeButton, String activeText, String detail) {
        mainHandler.post(() -> {
            loginButton.setEnabled(false);
            logoutButton.setEnabled(false);
            refreshButton.setEnabled(false);
            activeButton.setText(activeText);
            statusView.setText(detail);
        });
    }

    private void showResult(String status, String flow, boolean busy, boolean loggedIn) {
        showResult(status, flow, busy, loggedIn, loggedIn);
    }

    private void showResult(String status, String flow, boolean busy, boolean loggedIn, boolean notify) {
        mainHandler.post(() -> {
            loginButton.setEnabled(!busy);
            logoutButton.setEnabled(!busy);
            refreshButton.setEnabled(!busy);
            restoreButtonTexts();
            loginButton.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
            logoutButton.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
            refreshButton.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
            statusView.setText(status);
            flowView.setText(flow);
            if (notify) showBubble(status, bubbleColor(status, loggedIn));
        });
    }

    private int bubbleColor(String status, boolean loggedIn) {
        if (loggedIn) return 0xFF2563EB;
        if (containsAny(status, "\u5df2\u6ce8\u9500", "\u6ce8\u9500\u8bf7\u6c42")) return 0xFFF59E0B;
        return 0xFFDC2626;
    }

    private void restoreButtonTexts() {
        loginButton.setText("\u8054\u7f51");
        logoutButton.setText("\u6ce8\u9500");
        refreshButton.setText("\u5237\u65b0\u6d41\u91cf");
        loginButton.setScaleX(1f);
        loginButton.setScaleY(1f);
        logoutButton.setScaleX(1f);
        logoutButton.setScaleY(1f);
        refreshButton.setScaleX(1f);
        refreshButton.setScaleY(1f);
    }

    private void showBubble(String message, int color) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(18), dp(12), dp(18), dp(12));
        layout.setBackground(roundRect(color, color, dp(18), 0));

        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(isTvLike() ? 20 : 16);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.CENTER);
        layout.addView(text, new LinearLayout.LayoutParams(-2, -2));

        Toast toast = new Toast(getApplicationContext());
        toast.setView(layout);
        toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(isTvLike() ? 16 : 8));
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.show();
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        int selection = passwordInput.getSelectionStart();
        if (passwordVisible) {
            passwordInput.setTransformationMethod(null);
            passwordToggleButton.setImageResource(R.drawable.ic_eye);
        } else {
            passwordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
            passwordToggleButton.setImageResource(R.drawable.ic_eye_off);
        }
        passwordInput.setSelection(Math.max(0, selection));
    }

    private void finishTextInput() {
        passwordInput.clearFocus();
        hideKeyboard(passwordInput);
        operatorSpinner.requestFocus();
    }

    private void hideKeyboard(View view) {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private boolean handleTextInputConfirmKey(EditText input, int keyCode, KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_UP) return false;
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER
                && keyCode != KeyEvent.KEYCODE_ENTER
                && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return false;
        }
        showKeyboard(input);
        return true;
    }

    private void showKeyboard(EditText input) {
        input.requestFocus();
        int selection = input.getSelectionStart();
        input.setSelection(Math.max(0, selection));
        input.post(() -> {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void setFieldFocusStyle(EditText input, boolean hasFocus) {
        input.setBackground(roundRect(hasFocus ? 0xFFEFF6FF : 0xFFF8FAFC, hasFocus ? 0xFF1D4ED8 : 0xFFCBD5E1, dp(12), hasFocus ? dp(3) : dp(1)));
    }

    private void setAccountContainerFocusStyle(LinearLayout container, boolean hasFocus) {
        container.setBackground(roundRect(hasFocus ? 0xFFEFF6FF : 0xFFF8FAFC, hasFocus ? 0xFF1D4ED8 : 0xFFCBD5E1, dp(12), hasFocus ? dp(3) : dp(1)));
    }

    private void setPasswordContainerFocusStyle(LinearLayout container, boolean hasFocus) {
        container.setBackground(roundRect(hasFocus ? 0xFFEFF6FF : 0xFFF8FAFC, hasFocus ? 0xFF1D4ED8 : 0xFFCBD5E1, dp(12), hasFocus ? dp(3) : dp(1)));
    }

    private void setAccountDropdownButtonFocusStyle(boolean hasFocus) {
        accountDropdownButton.setColorFilter(hasFocus ? 0xFFFFFFFF : 0xFF0F766E);
        accountDropdownButton.setScaleX(1f);
        accountDropdownButton.setScaleY(1f);
        accountDropdownButton.setBackground(roundRect(hasFocus ? 0xFF1D4ED8 : 0xFFEFFCF9, hasFocus ? 0xFF1D4ED8 : 0xFFCCFBF1, dp(10), hasFocus ? 0 : dp(1)));
    }

    private void setPasswordToggleFocusStyle(boolean hasFocus) {
        passwordToggleButton.setColorFilter(hasFocus ? 0xFFFFFFFF : 0xFF0F766E);
        passwordToggleButton.setScaleX(1f);
        passwordToggleButton.setScaleY(1f);
        passwordToggleButton.setBackground(roundRect(hasFocus ? 0xFF1D4ED8 : 0xFFEFFCF9, hasFocus ? 0xFF1D4ED8 : 0xFFCCFBF1, dp(10), hasFocus ? 0 : dp(1)));
    }

    private static boolean isAlreadyOnline(String source) {
        return containsAny(source, "\u5df2\u7ecf\u5728\u7ebf", "\u5df2\u5728\u7ebf", "already online", "\"ret_code\":2", "\"ret_code\":\"2\"");
    }

    private static boolean isCurrentAccount(String source, String account) {
        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(source)) return false;
        String normalized = account.trim();
        return containsAny(
                source,
                "\"userName\":\"" + normalized + "\"",
                "\"userIdNumber\":\"" + normalized + "\"",
                "\"user_account\":\"" + normalized + "\"",
                "\"userAccount\":\"" + normalized + "\"",
                "\"user_name\":\"" + normalized + "\"",
                normalized + "@tust"
        );
    }

    private static String classifyLoginError(String source) {
        if (containsAny(source, "\u7edf\u4e00\u8eab\u4efd\u8ba4\u8bc1\u5bc6\u7801\u9519\u8bef", "\u5bc6\u7801\u9519\u8bef")) {
            return "\u767b\u5f55\u5931\u8d25\uff1a\u5bc6\u7801\u9519\u8bef";
        }
        if (containsAny(source, "domain error", "@unicom", "@cmcc", "@telecom")) {
            return "\u767b\u5f55\u5931\u8d25\uff1a\u4e0a\u7f51\u65b9\u5f0f\u9009\u62e9\u9519\u8bef";
        }
        return "\u767b\u5f55\u5931\u8d25\uff1a" + trimForDisplay(source);
    }

    private static boolean containsAny(String source, String... needles) {
        if (source == null) return false;
        for (String needle : needles) {
            if (source.contains(needle)) return true;
        }
        return false;
    }

    private static String trimForDisplay(String value) {
        if (TextUtils.isEmpty(value)) return "\u6821\u56ed\u7f51\u5173\u6ca1\u6709\u8fd4\u56de\u660e\u786e\u7ed3\u679c";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 120 ? compact.substring(0, 120) + "..." : compact;
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (TextUtils.isEmpty(message)) return "\u65e0\u8be6\u7ec6\u4fe1\u606f";
        return message.length() > 80 ? message.substring(0, 80) : message;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        int maxWidth = isTvLike() ? dp(760) : dp(520);
        params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(44), maxWidth);
        return params;
    }

    private GradientDrawable roundRect(int fill, int stroke, int radius, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }

    private boolean isTvLike() {
        return getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private String selectedOperator() {
        return String.valueOf(Math.max(0, operatorSpinner.getSelectedItemPosition()));
    }

    private static String operatorSuffix(String operator) {
        int index = safeOperatorIndex(operator);
        return OPERATOR_SUFFIXES[index];
    }

    private static int safeOperatorIndex(String value) {
        try {
            int index = Integer.parseInt(value);
            return index < 0 || index > 3 ? 0 : index;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String textOf(EditText input) {
        return input.getText().toString().trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class AccountDropdownAdapter extends ArrayAdapter<String> {
        AccountDropdownAdapter(List<String> accounts) {
            super(MainActivity.this, android.R.layout.simple_dropdown_item_1line, accounts);
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            return accountRow(position);
        }

        @Override
        public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
            return accountRow(position);
        }

        private View accountRow(int position) {
            TextView view = new TextView(MainActivity.this);
            view.setText(getItem(position));
            view.setSingleLine(true);
            view.setTextColor(0xFF0F172A);
            view.setTextSize(isTvLike() ? 19 : 16);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(18), 0, dp(18), 0);
            view.setMinHeight(dp(isTvLike() ? 58 : 48));
            view.setBackground(dropdownRowBackground());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(isTvLike() ? 62 : 52));
            params.setMargins(dp(8), dp(5), dp(8), dp(5));
            view.setLayoutParams(params);
            return view;
        }
    }

    private StateListDrawable dropdownRowBackground() {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_pressed}, roundRect(0xFFDBEAFE, 0xFF93C5FD, dp(12), dp(1)));
        drawable.addState(new int[]{android.R.attr.state_selected}, roundRect(0xFFE0F2FE, 0xFF38BDF8, dp(12), dp(1)));
        drawable.addState(new int[]{android.R.attr.state_focused}, roundRect(0xFFE0F2FE, 0xFF38BDF8, dp(12), dp(1)));
        drawable.addState(new int[]{}, roundRect(0xFFF1F5F9, 0x00FFFFFF, dp(12), 0));
        return drawable;
    }

    private static class DropdownArrowDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int width;
        private final int height;

        DropdownArrowDrawable(int color, int width, int height) {
            this.width = width;
            this.height = height;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            setBounds(0, 0, width, height);
        }

        @Override
        public void draw(Canvas canvas) {
            Path path = new Path();
            int left = getBounds().left;
            int top = getBounds().top;
            int right = getBounds().right;
            int bottom = getBounds().bottom;
            path.moveTo(left, top);
            path.lineTo(right, top);
            path.lineTo((left + right) / 2f, bottom);
            path.close();
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }
    }

    private static class FlowInfo {
        final long totalMb;
        final long remainingMb;
        final long usedMb;
        final long baseMb;
        final long onlineCount;
        final List<String> onlineIps;

        FlowInfo(long totalMb, long remainingMb, long usedMb, long baseMb, long onlineCount, List<String> onlineIps) {
            this.totalMb = totalMb;
            this.remainingMb = remainingMb;
            this.usedMb = usedMb;
            this.baseMb = baseMb;
            this.onlineCount = onlineCount;
            this.onlineIps = onlineIps == null ? new ArrayList<>() : onlineIps;
        }
    }

    private static class AccountRecord {
        final String password;
        final String operator;

        AccountRecord(String password, String operator) {
            this.password = password == null ? "" : password;
            this.operator = TextUtils.isEmpty(operator) ? "0" : operator;
        }
    }
}
