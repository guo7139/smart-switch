package com.smartswitch.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;

public class MainActivity extends Activity {
    private static final String SERVER = "http://192.168.70.153:8080";
    private LinearLayout rootLayout;
    private Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean polling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#F0F2F5"));
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(rootLayout);

        // 标题栏
        TextView title = new TextView(this);
        title.setText("智能照明控制");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1A1A2E"));
        title.setPadding(0, dp(8), 0, dp(16));
        title.setGravity(Gravity.CENTER);
        rootLayout.addView(title);

        setContentView(sv);
        loadDevices();
    }

    @Override
    protected void onResume() { super.onResume();
        refreshStatus(); polling = true; startPolling(); }
    @Override
    protected void onPause() { super.onPause(); polling = false; }

    private void startPolling() {
        handler.postDelayed(() -> {
            if (polling) { refreshStatus(); startPolling(); }
        }, 3000);
    }

    private void loadDevices() {
        new Thread(() -> {
            try {
                String json = httpGet(SERVER + "/api/devices");
                JSONObject resp = new JSONObject(json);
                if (resp.getInt("code") == 0) {
                    JSONArray data = resp.getJSONArray("data");
                    handler.post(() -> buildUI(data));
                }
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this, "连接服务器失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void refreshStatus() {
        new Thread(() -> {
            try {
                String json = httpGet(SERVER + "/api/devices");
                JSONObject resp = new JSONObject(json);
                if (resp.getInt("code") == 0) {
                    JSONArray data = resp.getJSONArray("data");
                    handler.post(() -> updateUI(data));
                }
            } catch (Exception e) { /* silent */ }
        }).start();
    }

    private void buildUI(JSONArray devices) {
        // 保留标题,清除其余
        while (rootLayout.getChildCount() > 1) rootLayout.removeViewAt(1);
        try {
            for (int i = 0; i < devices.length(); i++) {
                JSONObject dev = devices.getJSONObject(i);
                String name = dev.getString("name");
                boolean online = dev.optBoolean("online", false);

                // 设备卡片
                LinearLayout card = makeCard();
                // 设备标题行
                LinearLayout titleRow = new LinearLayout(this);
                titleRow.setOrientation(LinearLayout.HORIZONTAL);
                titleRow.setGravity(Gravity.CENTER_VERTICAL);
                TextView devTitle = new TextView(this);
                devTitle.setText(name);
                devTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                devTitle.setTypeface(null, Typeface.BOLD);
                devTitle.setTextColor(Color.parseColor("#1A1A2E"));
                titleRow.addView(devTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

                TextView statusBadge = new TextView(this);
                statusBadge.setText(online ? " ● 在线 " : " ○ 离线 ");
                statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                statusBadge.setTextColor(online ? Color.parseColor("#27AE60") : Color.parseColor("#E74C3C"));
                statusBadge.setTag("online_" + dev.getString("ip"));
                titleRow.addView(statusBadge);
                card.addView(titleRow);

                JSONArray modules = dev.getJSONArray("module");
                for (int j = 0; j < modules.length(); j++) {
                    JSONObject mod = modules.getJSONObject(j);
                    addModuleUI(card, dev, mod);
                }
                rootLayout.addView(card);
            }
        } catch (JSONException e) {
            Toast.makeText(this, "数据解析错误", Toast.LENGTH_SHORT).show();
        }
    }

    private void addModuleUI(LinearLayout card, JSONObject dev, JSONObject mod) throws JSONException {
        String ip = dev.getString("ip");
        int address = mod.getInt("address");
        int number = mod.getInt("number");
        String modTitle = mod.getString("title");
        JSONArray channels = mod.getJSONArray("channel");
        JSONObject status = mod.optJSONObject("status");

        // 模块标题 + 全开全关
        LinearLayout modRow = new LinearLayout(this);
        modRow.setOrientation(LinearLayout.HORIZONTAL);
        modRow.setGravity(Gravity.CENTER_VERTICAL);
        modRow.setPadding(0, dp(12), 0, dp(4));

        TextView mt = new TextView(this);
        mt.setText("📍 " + modTitle);
        mt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mt.setTextColor(Color.parseColor("#555555"));
        mt.setTypeface(null, Typeface.BOLD);
        modRow.addView(mt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button allOn = makeSmallBtn("全开", Color.parseColor("#27AE60"));
        allOn.setOnClickListener(v -> sendControlAll(ip, address, number, "on"));
        modRow.addView(allOn);

        Button allOff = makeSmallBtn("全关", Color.parseColor("#E74C3C"));
        allOff.setOnClickListener(v -> sendControlAll(ip, address, number, "off"));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.leftMargin = dp(8);
        modRow.addView(allOff, lp2);
        card.addView(modRow);

        // 分割线
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        card.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // 通道网格 (2列)
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(0, dp(8), 0, 0);

        for (int k = 0; k < channels.length(); k++) {
            String chName = channels.getString(k);
            boolean isOn = status != null && status.optBoolean(chName, false);
            int chNum = k + 1;

            LinearLayout chLayout = new LinearLayout(this);
            chLayout.setOrientation(LinearLayout.HORIZONTAL);
            chLayout.setGravity(Gravity.CENTER_VERTICAL);
            chLayout.setPadding(dp(8), dp(10), dp(8), dp(10));

            // 状态指示灯
            TextView indicator = new TextView(this);
            indicator.setText(isOn ? "💡" : "⚫");
            indicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            indicator.setTag("ind_" + ip + "_" + address + "_" + chName);
            chLayout.addView(indicator);

            TextView chLabel = new TextView(this);
            chLabel.setText("  " + chName);
            chLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            chLabel.setTextColor(Color.parseColor("#333333"));
            chLayout.addView(chLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            Switch sw = new Switch(this);
            sw.setChecked(isOn);
            sw.setTag("sw_" + ip + "_" + address + "_" + chName);
            sw.setOnCheckedChangeListener((btn, checked) -> {
                if (btn.isPressed()) {
                    sendControl(ip, address, chNum, checked ? "on" : "off");
                }
            });
            chLayout.addView(sw);

            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width = 0;
            gp.columnSpec = GridLayout.spec(k % 2, 1, 1f);
            gp.setMargins(dp(2), dp(2), dp(2), dp(2));
            grid.addView(chLayout, gp);
        }
        card.addView(grid);
    }

    private void updateUI(JSONArray devices) {
        try {
            for (int i = 0; i < devices.length(); i++) {
                JSONObject dev = devices.getJSONObject(i);
                String ip = dev.getString("ip");
                boolean online = dev.optBoolean("online", false);

                TextView badge = rootLayout.findViewWithTag("online_" + ip);
                if (badge != null) {
                    badge.setText(online ? " ● 在线 " : " ○ 离线 ");
                    badge.setTextColor(online ? Color.parseColor("#27AE60") : Color.parseColor("#E74C3C"));
                }

                JSONArray modules = dev.getJSONArray("module");
                for (int j = 0; j < modules.length(); j++) {
                    JSONObject mod = modules.getJSONObject(j);
                    int address = mod.getInt("address");
                    JSONArray channels = mod.getJSONArray("channel");
                    JSONObject status = mod.optJSONObject("status");
                    if (status == null) continue;

                    for (int k = 0; k < channels.length(); k++) {
                        String ch = channels.getString(k);
                        boolean isOn = status.optBoolean(ch, false);
                        String tag = ip + "_" + address + "_" + ch;

                        TextView ind = rootLayout.findViewWithTag("ind_" + tag);
                        if (ind != null) ind.setText(isOn ? "💡" : "⚫");

                        Switch sw = rootLayout.findViewWithTag("sw_" + tag);
                        if (sw != null && !sw.isPressed()) sw.setChecked(isOn);
                    }
                }
            }
        } catch (JSONException e) { /* ignore */ }
    }

    private void sendControl(String ip, int address, int channel, String action) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("ip", ip);
                body.put("address", address);
                body.put("channel", channel);
                body.put("action", action);
                String resp = httpPost(SERVER + "/api/control", body.toString());
                handler.post(() -> refreshStatus());
                JSONObject r = new JSONObject(resp);
                if (r.getInt("code") != 0) {
                    handler.post(() -> { Toast.makeText(this, r.optString("msg", "失败"), Toast.LENGTH_SHORT).show(); refreshStatus(); });
                }
            } catch (Exception e) {
                handler.post(() -> { Toast.makeText(this, "通信失败", Toast.LENGTH_SHORT).show(); refreshStatus(); });
            }
        }).start();
    }

    private void sendControlAll(String ip, int address, int number, String action) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("ip", ip); body.put("address", address);
                body.put("number", number); body.put("action", action);
                httpPost(SERVER + "/api/control_all", body.toString());
                handler.post(() -> refreshStatus());
            } catch (Exception e) {
                handler.post(() -> { Toast.makeText(this, "通信失败", Toast.LENGTH_SHORT).show(); refreshStatus(); });
            }
        }).start();
    }

    // ---- HTTP helpers ----
    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(5000);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line; while ((line = r.readLine()) != null) sb.append(line);
        r.close(); return sb.toString();
    }

    private String httpPost(String urlStr, String json) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setConnectTimeout(5000); c.setReadTimeout(5000);
        c.setDoOutput(true);
        c.getOutputStream().write(json.getBytes("UTF-8"));
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line; while ((line = r.readLine()) != null) sb.append(line);
        r.close(); return sb.toString();
    }

    // ---- UI helpers ----
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private Button makeSmallBtn(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(color);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setPadding(dp(12), dp(4), dp(12), dp(4));
        btn.setMinHeight(0); btn.setMinimumHeight(0);
        return btn;
    }
}
