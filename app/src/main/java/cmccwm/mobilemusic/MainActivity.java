package cmccwm.mobilemusic;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button theme;
    private Button chineseScript;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 1000L); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(36), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(16, 16, 16));

        TextView title = text("Spotify 悬浮歌词", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView body = text("无 Root 模式：只读取 Spotify 播放状态并显示悬浮歌词，不创建媒体会话，因此不会再触发 Car+ 自动暂停。\n\n1. 授予通知读取权限\n2. 授予悬浮窗权限\n3. 启动悬浮歌词\n4. 播放 Spotify 后进入 Car+\n\n悬浮窗顶部可拖动，点 × 可关闭。", 16, 0xffdddddd);
        LinearLayout.LayoutParams bodyLp = matchWrap(); bodyLp.topMargin = dp(22);
        root.addView(body, bodyLp);

        status = text("", 15, 0xff8ee6a8);
        LinearLayout.LayoutParams statusLp = matchWrap(); statusLp.topMargin = dp(20);
        root.addView(status, statusLp);

        Button notification = button("1. 打开通知读取权限");
        notification.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        addButton(root, notification, 22);

        Button overlay = button("2. 打开悬浮窗权限");
        overlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        addButton(root, overlay, 10);

        Button start = button("3. 启动悬浮歌词");
        start.setOnClickListener(v -> startOverlay());
        addButton(root, start, 10);

        theme = button("");
        theme.setOnClickListener(v -> cycleTheme());
        addButton(root, theme, 10);

        chineseScript = button("");
        chineseScript.setOnClickListener(v -> cycleChineseScript());
        addButton(root, chineseScript, 10);

        Button stop = button("停止悬浮歌词");
        stop.setOnClickListener(v -> stopService(new Intent(this, LyricsOverlayService.class)));
        addButton(root, stop, 10);

        Button reconnect = button("重新连接 Spotify");
        reconnect.setOnClickListener(v -> {
            DiagnosticLog.add(this, "用户点击重新连接 Spotify");
            SpotifyNotificationListener.requestReconnect(this);
        });
        addButton(root, reconnect, 10);

        Button diagnostics = button("复制诊断日志");
        diagnostics.setOnClickListener(v -> copyDiagnostics());
        addButton(root, diagnostics, 10);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
        Intent intent = new Intent(this, LyricsOverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private void updateStatus() {
        boolean listener = isListenerEnabled();
        boolean overlay = Settings.canDrawOverlays(this);
        BridgeState state = BridgeState.get();
        String now = state == null ? "等待 Spotify 播放" : state.getStatusText();
        boolean listenerConnected = SpotifyNotificationListener.isConnected();
        boolean mediaSessionConnected = state != null && state.hasSpotifySession();
        String spotifyConnection = listenerConnected ? "✓ 通知监听服务已连接"
                : mediaSessionConnected ? "✓ 已通过 Spotify 媒体会话连接（已自动绕过通知监听）"
                : "⚠ Spotify 连接未建立";
        status.setText((listener ? "✓ 通知读取权限" : "⚠ 未授予通知读取权限")
                + "\n" + (overlay ? "✓ 悬浮窗权限" : "⚠ 未授予悬浮窗权限")
                + "\n" + spotifyConnection
                + "\n" + (LyricsOverlayService.isRunning() ? "✓ 悬浮歌词运行中" : "○ 悬浮歌词未启动")
                + (TextUtils.isEmpty(now) ? "" : "\n" + now));
        updateThemeLabel();
        updateChineseScriptLabel();
    }

    private void cycleTheme() {
        int current = getSharedPreferences(LyricsOverlayService.PREFS, MODE_PRIVATE)
                .getInt(LyricsOverlayService.KEY_THEME, LyricsOverlayService.THEME_AUTO);
        int next = (current + 1) % 3;
        getSharedPreferences(LyricsOverlayService.PREFS, MODE_PRIVATE).edit()
                .putInt(LyricsOverlayService.KEY_THEME, next).apply();
        updateThemeLabel();
    }

    private void updateThemeLabel() {
        if (theme == null) return;
        int mode = getSharedPreferences(LyricsOverlayService.PREFS, MODE_PRIVATE)
                .getInt(LyricsOverlayService.KEY_THEME, LyricsOverlayService.THEME_AUTO);
        String name = mode == LyricsOverlayService.THEME_LIGHT ? "浅色"
                : mode == LyricsOverlayService.THEME_DARK ? "深色" : "自动（跟随系统）";
        theme.setText("显示模式：" + name);
    }

    private void cycleChineseScript() {
        int current = getSharedPreferences(LyricsOverlayService.PREFS, MODE_PRIVATE)
                .getInt(LyricsOverlayService.KEY_CHINESE_SCRIPT, LyricsOverlayService.SCRIPT_TRADITIONAL);
        int next = current == LyricsOverlayService.SCRIPT_TRADITIONAL
                ? LyricsOverlayService.SCRIPT_SIMPLIFIED : LyricsOverlayService.SCRIPT_TRADITIONAL;
        getSharedPreferences(LyricsOverlayService.PREFS, MODE_PRIVATE).edit()
                .putInt(LyricsOverlayService.KEY_CHINESE_SCRIPT, next).apply();
        updateChineseScriptLabel();
    }

    private void updateChineseScriptLabel() {
        if (chineseScript == null) return;
        int script = getSharedPreferences(LyricsOverlayService.PREFS, MODE_PRIVATE)
                .getInt(LyricsOverlayService.KEY_CHINESE_SCRIPT, LyricsOverlayService.SCRIPT_TRADITIONAL);
        chineseScript.setText(script == LyricsOverlayService.SCRIPT_SIMPLIFIED
                ? "中文歌词：简体中文" : "中文歌词：繁體中文");
    }

    private boolean isListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, SpotifyNotificationListener.class).flattenToString());
    }

    private void copyDiagnostics() {
        String report = "Spotify 悬浮歌词诊断日志\n"
                + "Android=" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "NotificationAccess=" + isListenerEnabled() + "\n"
                + "ListenerConnected=" + SpotifyNotificationListener.isConnected() + "\n"
                + "OverlayPermission=" + Settings.canDrawOverlays(this) + "\n"
                + "OverlayRunning=" + LyricsOverlayService.isRunning() + "\n"
                + "Status=" + (BridgeState.get() == null ? "未初始化" : BridgeState.get().getStatusText()) + "\n\n"
                + DiagnosticLog.dump(this);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Spotify 悬浮歌词诊断日志", report));
        Toast.makeText(this, "诊断日志已复制，可以直接粘贴发送", Toast.LENGTH_LONG).show();
    }

    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setTextSize(16); return b; }
    private void addButton(LinearLayout root, Button button, int top) {
        LinearLayout.LayoutParams lp = matchWrap(); lp.topMargin = dp(top); root.addView(button, lp);
    }
    private TextView text(String value, int sp, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(0, 1.2f); return v;
    }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
