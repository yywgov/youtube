package com.google.android.youtube.pro;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.google.android.youtube.pro.receivers.NotificationActionReceiver;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground media service: media session (lock screen, Bluetooth, headset),
 * notification with sleep timer, wake + wifi locks while playing,
 * "becoming noisy" (headphones unplugged) and network-return handling.
 *
 * All state changes arrive on the main thread from {@link com.google.android.youtube.pro.webview.WebAppInterface}.
 */
public class PlaybackService extends Service {

    public static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_PLAY = "ytl.PLAY";
    public static final String ACTION_PAUSE = "ytl.PAUSE";
    public static final String ACTION_NEXT = "ytl.NEXT";
    public static final String ACTION_PREV = "ytl.PREV";
    public static final String ACTION_SLEEP = "ytl.SLEEP";

    private static final long IDLE_STOP_MS = 30 * 60_000L;
    private static final long WAKE_LOCK_MS = 4 * 60 * 60_000L;

    // ---- now playing (static: survives service restarts, written on main thread) ----
    static String title = "";
    static String artist = "";
    static String artUrl = "";
    static long durationMs = 0;
    static int state = PlaybackState.STATE_NONE;
    static long positionMs = 0;
    static float rate = 1f;
    static long positionAt = 0;
    static Bitmap art;
    private static String artLoadedFor = "";

    private static PlaybackService instance;
    private static boolean starting = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private NotificationManager nm;
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean foreground = false;
    private BroadcastReceiver noisyReceiver;
    private ConnectivityManager.NetworkCallback netCallback;

    // ===================== static API (main thread) =====================

    public static void onMeta(Context c, String t, String a, String art, long dur) {
        title = t == null ? "" : t;
        artist = a == null ? "" : a;
        if (dur > 0) durationMs = dur;
        if (art != null && !art.equals(artUrl)) {
            artUrl = art;
            loadArt(art);
        }
        if (instance != null) instance.update();
        else if (state == PlaybackState.STATE_PLAYING) ensureRunning(c);
    }

    public static void onState(Context c, String s, long pos, float r) {
        int st;
        if ("playing".equals(s)) st = PlaybackState.STATE_PLAYING;
        else if ("buffering".equals(s)) st = PlaybackState.STATE_BUFFERING;
        else st = PlaybackState.STATE_PAUSED;
        state = st;
        positionMs = pos;
        rate = r > 0 ? r : 1f;
        positionAt = SystemClock.elapsedRealtime();
        if (instance != null) instance.update();
        else if (st == PlaybackState.STATE_PLAYING) ensureRunning(c);
    }

    public static void refresh() {
        if (instance != null) instance.update();
    }

    public static void stop(Context c) {
        state = PlaybackState.STATE_NONE;
        if (instance != null) instance.stopSelfNow();
        else c.stopService(new Intent(c, PlaybackService.class));
    }

    public static void handleAction(Context c, String action) {
        switch (action) {
            case ACTION_PLAY: MainActivity.js("ytl.play()"); break;
            case ACTION_PAUSE: MainActivity.js("ytl.pause()"); break;
            case ACTION_NEXT: MainActivity.js("ytl.next()"); break;
            case ACTION_PREV: MainActivity.js("ytl.prev()"); break;
            case ACTION_SLEEP: SleepTimer.cycle(c); break;
            default: break;
        }
    }

    private static void ensureRunning(Context c) {
        if (instance != null || starting) return;
        starting = true;
        Intent i = new Intent(c.getApplicationContext(), PlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.getApplicationContext().startForegroundService(i);
            else c.getApplicationContext().startService(i);
        } catch (Exception e) {
            starting = false;
        }
    }

    private static void loadArt(final String url) {
        IO.execute(() -> {
            Bitmap bmp = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                try (InputStream in = conn.getInputStream()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    bmp = BitmapFactory.decodeStream(in, null, o);
                }
                if (bmp != null && bmp.getWidth() > 640) {
                    int h = Math.round(bmp.getHeight() * (640f / bmp.getWidth()));
                    bmp = Bitmap.createScaledBitmap(bmp, 640, Math.max(1, h), true);
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            final Bitmap result = bmp;
            MAIN.post(() -> {
                if (url.equals(artUrl)) {
                    art = result;
                    artLoadedFor = url;
                    refresh();
                }
            });
        });
    }

    // ===================== service =====================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        starting = false;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        initSession();
        initLocks();
        registerNoisy();
        registerNetwork();
        goForeground(buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // must call startForeground() promptly after startForegroundService()
        goForeground(buildNotification());
        update();
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelfNow();
    }

    @Override
    public void onDestroy() {
        MAIN.removeCallbacks(idleStop);
        releaseLocks();
        try { if (noisyReceiver != null) unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        try {
            if (netCallback != null && Build.VERSION.SDK_INT >= 24) {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback);
            }
        } catch (Exception ignored) {}
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        stopFg(true);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        instance = null;
        starting = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopSelfNow() {
        stopFg(true);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台播放", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    private void initSession() {
        session = new MediaSession(this, "YTLite");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.js("ytl.play()"); }
            @Override public void onPause() { MainActivity.js("ytl.pause()"); }
            @Override public void onStop() { MainActivity.js("ytl.pause()"); }
            @Override public void onSkipToNext() { MainActivity.js("ytl.next()"); }
            @Override public void onSkipToPrevious() { MainActivity.js("ytl.prev()"); }
            @Override public void onSeekTo(long pos) { MainActivity.js("ytl.seek(" + pos + ")"); }
            @Override public void onCustomAction(String action, android.os.Bundle extras) {
                if (ACTION_SLEEP.equals(action)) SleepTimer.cycle(PlaybackService.this);
            }
        });
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 10, open, piFlags()));
        session.setActive(true);
    }

    @SuppressLint("WakelockTimeout")
    private void initLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytlite:playback");
        wakeLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            //noinspection deprecation
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ytlite:wifi");
            wifiLock.setReferenceCounted(false);
        }
    }

    private void acquireLocks() {
        try { if (wakeLock != null) wakeLock.acquire(WAKE_LOCK_MS); } catch (Exception ignored) {}
        try { if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire(); } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    }

    private void registerNoisy() {
        noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())
                        && state == PlaybackState.STATE_PLAYING) {
                    MainActivity.js("ytl.noisy()");
                }
            }
        };
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, f);
    }

    private void registerNetwork() {
        if (Build.VERSION.SDK_INT < 24) return; // JS "online" event covers older devices
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    MAIN.postDelayed(() -> MainActivity.js("ytl.onNet()"), 1500);
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
    }

    /** Re-render session + notification from the static state. */
    private void update() {
        boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;

        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
        if (art != null && artUrl.equals(artLoadedFor)) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(mb.build());

        String sleep = SleepTimer.status();
        PlaybackState.Builder pb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(state == PlaybackState.STATE_NONE ? PlaybackState.STATE_PAUSED : state,
                        positionMs, state == PlaybackState.STATE_PLAYING ? rate : 0f, positionAt)
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        ACTION_SLEEP, sleep != null ? sleep : "睡眠定时", R.drawable.ic_timer_white).build());
        session.setPlaybackState(pb.build());

        Notification n = buildNotification();
        if (playing) {
            MAIN.removeCallbacks(idleStop);
            acquireLocks();
            if (!foreground) goForeground(n);
            else nm.notify(NOTIFICATION_ID, n);
        } else {
            releaseLocks();
            if (foreground && MainActivity.isVisible()) stopFg(false); // dismissible while paused in app
            nm.notify(NOTIFICATION_ID, n);
            MAIN.removeCallbacks(idleStop);
            MAIN.postDelayed(idleStop, IDLE_STOP_MS);
        }
    }

    private final Runnable idleStop = () -> {
        if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_BUFFERING) stopSelfNow();
    };

    private void goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION_ID, n);
            foreground = true;
        } catch (Exception e) {
            // e.g. ForegroundServiceStartNotAllowedException when resumed from the background
            try { nm.notify(NOTIFICATION_ID, n); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private void stopFg(boolean remove) {
        if (!foreground) return;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        else stopForeground(remove);
        foreground = false;
    }

    private static int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, NotificationActionReceiver.class).setAction(a);
        return PendingIntent.getBroadcast(this, code, i, piFlags());
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);

        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String sleep = SleepTimer.status();

        b.setSmallIcon(R.drawable.notification)
                .setContentTitle(title.isEmpty() ? getString(R.string.app_name) : title)
                .setContentText(artist)
                .setContentIntent(PendingIntent.getActivity(this, 11, open, piFlags()))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(R.drawable.ic_skip_previous_white, "上一个", action(ACTION_PREV, 1)).build())
                .addAction(playing
                        ? new Notification.Action.Builder(R.drawable.ic_pause_white, "暂停", action(ACTION_PAUSE, 2)).build()
                        : new Notification.Action.Builder(R.drawable.ic_play_arrow_white, "播放", action(ACTION_PLAY, 3)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_skip_next_white, "下一个", action(ACTION_NEXT, 4)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_timer_white,
                        sleep != null ? sleep : "睡眠定时", action(ACTION_SLEEP, 5)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (sleep != null) b.setSubText(sleep);
        if (art != null && artUrl.equals(artLoadedFor)) b.setLargeIcon(art);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
