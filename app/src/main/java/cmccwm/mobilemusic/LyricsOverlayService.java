package cmccwm.mobilemusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class LyricsOverlayService extends Service {
    private static final String CHANNEL = "spotify_overlay_lyrics";
    static final String PREFS = "overlay_preferences";
    static final String KEY_THEME = "theme";
    static final String KEY_CHINESE_SCRIPT = "chinese_script";
    static final String KEY_ENABLED = "overlay_enabled";
    static final String KEY_DISPLAY_ID = "overlay_display_id";
    static final String EXTRA_DISPLAY_ID = "display_id";
    static final int THEME_AUTO = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;
    static final int SCRIPT_SIMPLIFIED = 0;
    static final int SCRIPT_TRADITIONAL = 1;
    private static volatile boolean running;
    private static volatile long lastRecoveryAttempt;
    static boolean isRunning() { return running; }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    static void requestStart(Context context, int displayId) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_DISPLAY_ID, displayId)
                .apply();
        pendingDisplayId = displayId;
        start(context, "用户启动");
    }

    static void requestStop(Context context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply();
        context.stopService(new Intent(context, LyricsOverlayService.class));
    }

    static void recoverIfEnabled(Context context, String reason) {
        if (!isEnabled(context) || running || !Settings.canDrawOverlays(context)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRecoveryAttempt < 15000L) return;
        lastRecoveryAttempt = now;
        start(context, "自动恢复：" + reason);
    }

    private static void start(Context context, String reason) {
        try {
            Intent intent = new Intent(context, LyricsOverlayService.class)
                    .putExtra(EXTRA_DISPLAY_ID, pendingDisplayId);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
            DiagnosticLog.add(context, "请求启动悬浮歌词（" + reason + "）");
        } catch (Throwable error) {
            DiagnosticLog.add(context, "启动悬浮歌词失败（" + reason + "）=" + error.getClass().getSimpleName());
        }
    }

    private static volatile int pendingDisplayId = Display.DEFAULT_DISPLAY;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private Context overlayContext;
    private int overlayDisplayId = -1;
    private WindowManager.LayoutParams params;
    private LinearLayout panel;
    private TextView previous, current, next;
    private Button close;
    private int appliedTheme = -1;
    private boolean darkTheme;
    private long lastSessionScan;
    private long lastDisplayScan;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 180L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        DiagnosticLog.add(this, "悬浮歌词服务 onCreate");
        createChannel();
        startForeground(2026, notification());
        if (!Settings.canDrawOverlays(this)) {
            DiagnosticLog.add(this, "悬浮窗权限缺失，停止服务");
            stopSelf();
            return;
        }
        running = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) stopSelf();
        int requestedDisplayId = intent == null
                ? getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                : intent.getIntExtra(EXTRA_DISPLAY_ID,
                        getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_DISPLAY_ID, Display.DEFAULT_DISPLAY));
        pendingDisplayId = requestedDisplayId;
        moveOverlayIfNeeded(true);
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        DiagnosticLog.add(this, "悬浮歌词服务 onDestroy");
        running = false;
        handler.removeCallbacks(refresh);
        if (panel != null && windowManager != null) {
            try { windowManager.removeView(panel); } catch (Throwable ignored) { }
        }
        panel = null;
        overlayContext = null;
        overlayDisplayId = -1;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void showOverlay(Display display) {
        overlayDisplayId = display.getDisplayId();
        overlayContext = overlayDisplayId == Display.DEFAULT_DISPLAY
                ? this : createDisplayContext(display);
        windowManager = (WindowManager) overlayContext.getSystemService(WINDOW_SERVICE);
        panel = new LinearLayout(overlayContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(2), dp(16), dp(4));
        panel.setElevation(dp(10));

        LinearLayout header = new LinearLayout(overlayContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View dragArea = new View(overlayContext);
        header.addView(dragArea, new LinearLayout.LayoutParams(0, -1, 1f));
        close = new Button(overlayContext);
        close.setText("×"); close.setTextSize(16); close.setTextColor(0xff303134);
        close.setBackgroundColor(Color.TRANSPARENT); close.setMinWidth(0); close.setMinHeight(0);
        close.setPadding(dp(8), 0, dp(3), 0);
        close.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply();
            stopSelf();
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(30), dp(22)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(20)));

        previous = text("", 16, 0xff9aa0a6, 1);
        current = text("等待 Spotify 播放", 23, 0xff151515, 2);
        next = text("", 17, 0xff7c8288, 1);
        configureFixedLine(previous, 12, 16);
        configureFixedLine(current, 15, 23);
        configureFixedLine(next, 12, 17);
        panel.addView(previous, row(dp(36), 0));
        panel.addView(current, row(dp(70), 1));
        panel.addView(next, row(dp(36), 1));

        params = new WindowManager.LayoutParams(
                dp(350), dp(176),
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        // Grow upward into Car+'s unused gap instead of covering the clock area below.
        params.x = dp(18); params.y = dp(110);
        attachDrag(header);
        windowManager.addView(panel, params);
        DiagnosticLog.add(this, "悬浮窗已显示到 Display " + overlayDisplayId
                + "（" + display.getName() + "，" + display.getMode().getPhysicalWidth()
                + "x" + display.getMode().getPhysicalHeight() + "）");
        applyTheme(resolveTheme());
    }

    private Display resolveTargetDisplay() {
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        int requested = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_DISPLAY_ID, pendingDisplayId);
        Display exact = manager.getDisplay(requested);
        if (exact != null && exact.getState() != Display.STATE_OFF && requested != Display.DEFAULT_DISPLAY) {
            return exact;
        }
        Display best = null;
        long bestPixels = -1L;
        for (Display candidate : manager.getDisplays()) {
            if (candidate.getDisplayId() == Display.DEFAULT_DISPLAY || candidate.getState() == Display.STATE_OFF) continue;
            Display.Mode mode = candidate.getMode();
            long pixels = (long) mode.getPhysicalWidth() * mode.getPhysicalHeight();
            boolean mirage = candidate.getDisplayId() >= 10000
                    || candidate.getName().toLowerCase().contains("car")
                    || candidate.getName().toLowerCase().contains("mirage");
            if (mirage) pixels += Long.MAX_VALUE / 4;
            if (pixels > bestPixels) { bestPixels = pixels; best = candidate; }
        }
        if (best != null) return best;
        Display fallback = manager.getDisplay(Display.DEFAULT_DISPLAY);
        return fallback != null ? fallback : getDisplay();
    }

    private void moveOverlayIfNeeded(boolean force) {
        Display target = resolveTargetDisplay();
        if (target == null || (!force && panel != null && overlayDisplayId == target.getDisplayId())) return;
        if (panel != null && windowManager != null) {
            try { windowManager.removeView(panel); } catch (Throwable ignored) { }
            panel = null;
        }
        try {
            showOverlay(target);
        } catch (Throwable error) {
            DiagnosticLog.add(this, "Display " + target.getDisplayId() + " 创建悬浮窗失败="
                    + error.getClass().getSimpleName());
            DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display fallback = manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (fallback != null && fallback.getDisplayId() != target.getDisplayId()) showOverlay(fallback);
        }
    }

    private void attachDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY; float downX, downY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = params.x; startY = params.y; downX = event.getRawX(); downY = event.getRawY(); return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x = startX - Math.round(event.getRawX() - downX);
                    params.y = startY + Math.round(event.getRawY() - downY);
                    windowManager.updateViewLayout(panel, params); return true;
                }
                return event.getAction() == MotionEvent.ACTION_UP;
            }
        });
    }

    private void render() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDisplayScan >= 2000L) {
            lastDisplayScan = now;
            moveOverlayIfNeeded(false);
        }
        if (panel == null) return;
        if (now - lastSessionScan >= 5000L) {
            lastSessionScan = now;
            SpotifyNotificationListener.scanFrom(this);
        }
        int resolvedTheme = resolveTheme();
        if (resolvedTheme != appliedTheme) applyTheme(resolvedTheme);
        BridgeState state = BridgeState.ensure(this);
        BridgeState.Snapshot snapshot = state.snapshot();
        previous.setText(snapshot.previous);
        current.setText(snapshot.current);
        next.setText(snapshot.next);
        current.setTextColor(snapshot.playing
                ? (darkTheme ? 0xfff7f8fa : 0xff151515)
                : (darkTheme ? 0xffa6abb2 : 0xff74787d));
    }

    private int resolveTheme() {
        int preference = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_THEME, THEME_AUTO);
        if (preference == THEME_LIGHT || preference == THEME_DARK) return preference;
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? THEME_DARK : THEME_LIGHT;
    }

    private void applyTheme(int theme) {
        appliedTheme = theme;
        darkTheme = theme == THEME_DARK;
        GradientDrawable background = new GradientDrawable();
        background.setColor(darkTheme ? 0xff17191d : 0xfff7f8fa);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), darkTheme ? 0x33ffffff : 0x22000000);
        panel.setBackground(background);
        close.setTextColor(darkTheme ? 0xfff4f4f4 : 0xff303134);
        previous.setTextColor(darkTheme ? 0xff78818d : 0xff9aa0a6);
        next.setTextColor(darkTheme ? 0xffb0b6bf : 0xff7c8288);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Spotify 悬浮歌词", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持悬浮歌词运行");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Spotify 悬浮歌词运行中")
                .setContentText("点此调整权限或停止悬浮歌词")
                .setContentIntent(pending).setOngoing(true).build();
    }

    private TextView text(String value, int sp, int color, int maxLines) {
        TextView view = new TextView(overlayContext == null ? this : overlayContext);
        view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        view.setGravity(Gravity.CENTER); view.setMaxLines(maxLines); view.setLineSpacing(0, 1.15f);
        return view;
    }
    private void configureFixedLine(TextView view, int minSp, int maxSp) {
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP);
    }
    private LinearLayout.LayoutParams row(int heightPx, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, heightPx);
        p.topMargin = dp(topDp);
        return p;
    }
    private int dp(int value) {
        Context context = overlayContext == null ? this : overlayContext;
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
