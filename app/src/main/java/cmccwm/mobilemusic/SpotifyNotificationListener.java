package cmccwm.mobilemusic;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.List;

public final class SpotifyNotificationListener extends NotificationListenerService {
    private static final String SPOTIFY = "com.spotify.music";
    private static final long RESCAN_INTERVAL_MS = 5000L;
    private static volatile SpotifyNotificationListener instance;
    private static volatile boolean fallbackSessionFound;
    static boolean isConnected() {
        SpotifyNotificationListener current = instance;
        return current != null && current.listenerConnected;
    }

    static boolean scanFrom(Context context) {
        SpotifyNotificationListener current = instance;
        if (current != null && current.listenerConnected) {
            current.scanActive();
            return current.controller != null;
        }
        try {
            MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> sessions = manager.getActiveSessions(
                    new ComponentName(context, SpotifyNotificationListener.class));
            for (MediaController candidate : sessions) {
                if (SPOTIFY.equals(candidate.getPackageName())) {
                    BridgeState.ensure(context).attachSpotify(candidate);
                    if (!fallbackSessionFound) {
                        fallbackSessionFound = true;
                        DiagnosticLog.add(context, "通知监听未连接，通过悬浮服务直接找到 Spotify MediaSession");
                    }
                    return true;
                }
            }
            if (fallbackSessionFound) DiagnosticLog.add(context, "悬浮服务未找到 Spotify MediaSession");
            fallbackSessionFound = false;
        } catch (Throwable error) {
            DiagnosticLog.add(context, "悬浮服务扫描 MediaSession 异常=" + error.getClass().getSimpleName());
        }
        return false;
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaController controller;
    private boolean listenerConnected;
    private final Runnable rescan = new Runnable() {
        @Override public void run() {
            if (!listenerConnected) return;
            scanActive();
            handler.postDelayed(this, RESCAN_INTERVAL_MS);
        }
    };
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(android.media.MediaMetadata metadata) { publish(); }
        @Override public void onPlaybackStateChanged(android.media.session.PlaybackState state) { publish(); }
        @Override public void onSessionDestroyed() {
            controller = null;
            BridgeState.ensure(SpotifyNotificationListener.this).detachSpotify();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        DiagnosticLog.add(this, "通知监听服务 onCreate");
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        listenerConnected = true;
        DiagnosticLog.add(this, "通知监听服务已连接");
        LyricsOverlayService.recoverIfEnabled(this, "通知监听已连接");
        handler.removeCallbacks(rescan);
        scanActive();
        handler.postDelayed(rescan, RESCAN_INTERVAL_MS);
    }

    @Override public void onListenerDisconnected() {
        listenerConnected = false;
        DiagnosticLog.add(this, "通知监听服务断开，申请重新绑定");
        handler.removeCallbacks(rescan);
        clearController();
        BridgeState.ensure(this).detachSpotify();
        super.onListenerDisconnected();
        try { requestRebind(new ComponentName(this, SpotifyNotificationListener.class)); } catch (Throwable ignored) { }
    }

    @Override public void onDestroy() {
        listenerConnected = false;
        DiagnosticLog.add(this, "通知监听服务 onDestroy");
        handler.removeCallbacks(rescan);
        clearController();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (SPOTIFY.equals(sbn.getPackageName())) {
            LyricsOverlayService.recoverIfEnabled(this, "收到 Spotify 通知");
            boolean attached = attach(sbn.getNotification());
            DiagnosticLog.add(this, "收到 Spotify 通知，媒体会话令牌=" + attached);
        }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (SPOTIFY.equals(sbn.getPackageName()) && controller != null) publish();
    }

    private void scanActive() {
        int spotifyNotifications = 0;
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) {
                for (StatusBarNotification sbn : all) {
                    if (SPOTIFY.equals(sbn.getPackageName())) {
                        spotifyNotifications++;
                        if (attach(sbn.getNotification())) {
                            DiagnosticLog.add(this, "扫描通知成功，Spotify 通知数=" + spotifyNotifications);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable error) {
            DiagnosticLog.add(this, "扫描通知异常=" + error.getClass().getSimpleName());
        }

        try {
            MediaSessionManager manager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> sessions = manager.getActiveSessions(
                    new ComponentName(this, SpotifyNotificationListener.class));
            for (MediaController candidate : sessions) {
                if (SPOTIFY.equals(candidate.getPackageName())) {
                    DiagnosticLog.add(this, "通过 MediaSession 回退连接 Spotify，会话总数=" + sessions.size());
                    bind(candidate);
                    return;
                }
            }
            DiagnosticLog.add(this, "未找到 Spotify 媒体会话，Spotify 通知数=" + spotifyNotifications
                    + "，媒体会话总数=" + sessions.size());
        } catch (Throwable error) {
            DiagnosticLog.add(this, "扫描 MediaSession 异常=" + error.getClass().getSimpleName());
        }
    }

    private void clearController() {
        if (controller != null) {
            try { controller.unregisterCallback(callback); } catch (Throwable ignored) { }
            controller = null;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean attach(Notification notification) {
        MediaSession.Token token = notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        if (token == null) return false;
        if (controller != null && controller.getSessionToken().equals(token)) {
            publish();
            return true;
        }
        bind(new MediaController(this, token));
        return true;
    }

    private void bind(MediaController next) {
        clearController();
        controller = next;
        controller.registerCallback(callback);
        DiagnosticLog.add(this, "已绑定 Spotify MediaController");
        publish();
    }

    private void publish() { BridgeState.ensure(this).attachSpotify(controller); }

    static void requestReconnect(Context context) {
        SpotifyNotificationListener current = instance;
        if (current != null) current.handler.post(current::scanActive);
        else {
            DiagnosticLog.add(context, "重新连接时通知监听服务实例不存在，改用 MediaSession 直连");
            scanFrom(context);
        }
        try { requestRebind(new ComponentName(context, SpotifyNotificationListener.class)); } catch (Throwable ignored) { }
    }
}
