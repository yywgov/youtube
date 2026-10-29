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
    public static final String ACTION_REW = "ytl.REW";
    public static final String ACTION_FWD = "ytl.FWD";
    public static final String ACTION_EXTEND = "ytl.EXTEND";
    public static final String ACTION_WL = "ytl.WATCH_LATER";
    public static final String ACTION_CLOSE = "ytl.CLOSE";       // paused: stop and remove the notification (290)
    public static final String ACTION_DISMISS = "ytl.DISMISS";   // notification swiped away (290)
    /** Content intent extra: open the app and continue the last video (243). */
    public static final String EXTRA_RESUME = "ytl.resume";
    /** After the timer stopped playback the session stays 10 minutes (302): one headset press plays on. */
    private static final long SLEEP_DONE_STOP_MS = 10 * 60_000L;
    /** Timer ended and nothing happened for 5 minutes: the page's timers are paused too (273). */
    private static final long SLEEP_DONE_FREEZE_MS = 5 * 60_000L;
    /** Inactivity guard (277): this long without any input while playing, no timer: 30 min timer. */
    static final long IDLE_GUARD_MS = 3 * 3600_000L;
    private static final int IDLE_GUARD_TIMER_MIN = 30;
    private static final long MULTI_CLICK_MS = 450L;

    private static final long IDLE_STOP_MS = 30 * 60_000L;
    /** Paused in the background this long: leave the foreground, notification becomes dismissible (290). */
    private static final long IDLE_DETACH_MS = 5 * 60_000L;
    /** Buffering this long (network gone at night): let go of the wake / Wi-Fi locks (272). */
    private static final long LONG_BUFFER_MS = 10 * 60_000L;
    private static final long WAKE_LOCK_MS = 4 * 60 * 60_000L;

    // ---- now playing (static: survives service restarts, written on main thread) ----
    static String title = "";
    static String artist = "";
    static String artUrl = "";
    static String videoId = "";
    static long durationMs = 0;
    static int state = PlaybackState.STATE_NONE;
    static long positionMs = 0;
    static float rate = 1f;
    static long positionAt = 0;
    static Bitmap art;
    private static String artLoadedFor = "";
    /** Live stream right now (294): no duration and no seeking on the lock screen. */
    static boolean live = false;
    /** Last 8 notification pictures (297): switching back and forth or looping does not download again. */
    private static final android.util.LruCache<String, Bitmap> ART_CACHE = new android.util.LruCache<>(8);
    /** The sleep timer paused playback: drop foreground + locks and stop soon (17). */
    private static boolean sleepDone = false;
    private static long lastVolHint = 0;
    /** The activity (and with it the WebView) was destroyed by the system while playing (243). */
    private static boolean interrupted = false;
    private static boolean longBuffer = false;
    private static long lastDeniedLog = 0;
    /** The page reported a network stall / gave up retrying (250): said in the notification. */
    private static boolean netNote = false;
    /** Last touch / key / media button / notification action (277), elapsedRealtime. */
    private static long lastInputAt = SystemClock.elapsedRealtime();
    /** Buffering events with the screen off (271): evidence for the Wi-Fi lock choice. */
    private static int bgBuffering = 0;

    private static PlaybackService instance;
    private static boolean starting = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private NotificationManager nm;
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean foreground = false;
    private BroadcastReceiver noisyReceiver;
    private ConnectivityManager.NetworkCallback netCallback;

    // ===================== static API (main thread) =====================

    public static void onMeta(Context c, String t, String a, String art, long dur, String id) {
        title = t == null ? "" : t;
        artist = a == null ? "" : a;
        videoId = id == null ? "" : id;
        if (dur > 0) durationMs = dur;
        if (art != null && !art.equals(artUrl)) {
            artUrl = art;
            Bitmap hit = ART_CACHE.get(art);
            if (hit != null) { PlaybackService.art = hit; artLoadedFor = art; }
            else loadArt(art);
        }
        if (instance != null) instance.update();
        else if (state == PlaybackState.STATE_PLAYING) ensureRunning(c);
    }

    public static void onState(Context c, String s, long pos, float r) {
        int st;
        if ("playing".equals(s)) st = PlaybackState.STATE_PLAYING;
        else if ("buffering".equals(s)) st = PlaybackState.STATE_BUFFERING;
        else st = PlaybackState.STATE_PAUSED;
        if (st == PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_PLAYING) {
            sleepDone = false;
            volumeHint(c);
        }
        boolean wasPlaying = isPlaying();
        if (st != PlaybackState.STATE_BUFFERING) longBuffer = false;
        if (st == PlaybackState.STATE_PLAYING) { interrupted = false; netNote = false; }
        if (st == PlaybackState.STATE_BUFFERING && state == PlaybackState.STATE_PLAYING && !MainActivity.isVisible()) bgBuffering++;
        state = st;
        positionMs = pos;
        rate = r > 0 ? r : 1f;
        positionAt = SystemClock.elapsedRealtime();
        if (wasPlaying != isPlaying()) SleepTimer.onPlayback(c, isPlaying());   // pause holds the timer (305)
        if (instance != null) instance.update();
        else if (st == PlaybackState.STATE_PLAYING) ensureRunning(c);
    }

    public static void refresh() {
        if (instance != null) instance.update();
    }

    /** The page says whether the video is a live stream (294). */
    public static void setLive(boolean on) {
        if (live == on) return;
        live = on;
        if (instance != null) instance.update();
    }

    /** Page says the network is gone / retries gave up (250); cleared by playback or the network coming back. */
    public static void setNetNote(boolean on) {
        if (netNote == on) return;
        netNote = on;
        if (on) Diag.log("net", "stalled while playing: shown in the notification");
        if (instance != null) instance.update();
    }

    public static boolean netNote() { return netNote; }

    /** Any touch, key or media command (277). */
    public static void noteInput() { lastInputAt = SystemClock.elapsedRealtime(); }

    /** Playback position now, extrapolated while playing (242). */
    public static long positionNowMs() {
        long p = positionMs;
        if (state == PlaybackState.STATE_PLAYING && positionAt > 0) p += (long) ((SystemClock.elapsedRealtime() - positionAt) * rate);
        return Math.max(0, p);
    }

    public static String videoId() { return videoId; }

    /**
     * A command for the page from the notification / media session. Without a page (the system took
     * the activity, 296) it can't be carried out: the notification becomes "tap to continue".
     */
    static void cmd(Context c, String js) {
        noteInput();
        if (!MainActivity.hasPage()) {
            Diag.log("cmd", "no page for " + js);
            onActivityGone();
            try { android.widget.Toast.makeText(c.getApplicationContext(), "点通知打开 App 继续播放", android.widget.Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            return;
        }
        MainActivity.js(js);
    }

    /**
     * The system destroyed the activity while something was playing (243): the WebView is gone, so
     * playback stopped. Keep a paused, dismissible "tap to continue" notification instead of
     * stopping everything; the sleep timer state is kept (and persisted, 244).
     */
    public static void onActivityGone() {
        if (state == PlaybackState.STATE_NONE && instance == null) return;
        interrupted = true;
        state = PlaybackState.STATE_PAUSED;
        positionAt = SystemClock.elapsedRealtime();
        if (instance != null) instance.update();
    }

    public static boolean isForeground() {
        return instance != null && instance.foreground;
    }

    /** App back on screen after a refused foreground start (291): try again while it is allowed. */
    public static void retryForeground() {
        if (instance != null && !instance.foreground && isPlaying()) {
            instance.lastNotifKey = "";
            instance.goForeground(instance.buildNotification());
        }
    }

    // the local watch-time statistics (159) were removed (478): no write to preferences on every pause any more

    /** Sleep timer ended (paused): release everything that keeps the phone awake (17). */
    public static boolean isPlaying() {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
    }

    public static void onSleepEnded() {
        sleepDone = true;
        MainActivity.onSleepEnded();
        if (instance != null) {
            instance.update();
            MAIN.removeCallbacks(instance.freezeRun);
            MAIN.postDelayed(instance.freezeRun, SLEEP_DONE_FREEZE_MS);
        }
    }

    /** 5 minutes after the timer stopped playback with nothing happening (273): pause the page's timers. */
    private final Runnable freezeRun = () -> {
        if (sleepDone && !isPlaying() && !MainActivity.isVisible()) MainActivity.freezeWeb();
    };

    /** Playing with the media volume at 0: say so once in a while (150), only when the app is on screen. */
    private static void volumeHint(Context c) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (!MainActivity.isVisible() || now - lastVolHint < 10 * 60_000L) return;
            AudioManager am = (AudioManager) c.getApplicationContext().getSystemService(AUDIO_SERVICE);
            if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                lastVolHint = now;
                MainActivity.notifyText(c, "媒体音量为 0，按音量键调大");
            }
        } catch (Exception ignored) {}
    }

    public static void stop(Context c) {
        state = PlaybackState.STATE_NONE;
        if (instance != null) instance.stopSelfNow();
        else c.stopService(new Intent(c, PlaybackService.class));
    }

    public static void handleAction(Context c, String action) {
        noteInput();
        switch (action) {
            case ACTION_PLAY: cmd(c, "ytl.play()"); break;
            case ACTION_PAUSE: cmd(c, "ytl.pause()"); break;
            case ACTION_NEXT: cmd(c, "ytl.next()"); break;
            case ACTION_PREV: cmd(c, "ytl.prev()"); break;
            case ACTION_SLEEP: SleepTimer.cycle(c); break;
            case ACTION_REW: cmd(c, "ytl.seekBy(-10)"); break;
            case ACTION_FWD: cmd(c, "ytl.seekBy(30)"); break;
            case ACTION_EXTEND: SleepTimer.extend(c); break;
            case ACTION_WL: cmd(c, "ytl.watchLater()"); break;
            case ACTION_CLOSE:
                if (MainActivity.hasPage()) MainActivity.js("ytl.pause()");
                stop(c);
                break;
            case ACTION_DISMISS:
                if (!isPlaying()) stop(c);
                break;
            default: break;
        }
    }

    private static void ensureRunning(Context c) {
        if (instance != null || starting) return;
        starting = true;
        Intent i = new Intent(c.getApplicationContext(), PlaybackService.class);
        try {
            c.getApplicationContext().startForegroundService(i);   // minSdk 26 (419)
        } catch (Exception e) {
            starting = false;
        }
    }

    private static void loadArt(final String url) {
        Bg.NET.execute(() -> {
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
                // hqdefault is 4:3 with black bars around a 16:9 picture: crop them (116/117)
                if (bmp != null && bmp.getHeight() > 0) {
                    float ratio = bmp.getWidth() / (float) bmp.getHeight();
                    if (ratio > 1.30f && ratio < 1.36f) {
                        int h = Math.round(bmp.getWidth() * 9f / 16f);
                        int y = (bmp.getHeight() - h) / 2;
                        if (h > 0 && y > 0) bmp = Bitmap.createBitmap(bmp, 0, y, bmp.getWidth(), h);
                    }
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
                if (result != null) ART_CACHE.put(url, result);
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
        MAIN.removeCallbacks(idleGuardRun);
        MAIN.postDelayed(idleGuardRun, IDLE_CHECK_MS);
    }

    // ---- inactivity guard (277): checked every 10 minutes while the service runs ----
    private static final long IDLE_CHECK_MS = 10 * 60_000L;
    private final Runnable idleGuardRun = new Runnable() {
        @Override public void run() {
            try {
                long now = SystemClock.elapsedRealtime();
                if (Logic.idleDue(Prefs.on(PlaybackService.this, Prefs.IDLE_GUARD), state == PlaybackState.STATE_PLAYING,
                        SleepTimer.active(), lastInputAt, now, IDLE_GUARD_MS)) {
                    Diag.log("idle", "no input for 3 h while playing: 30 min sleep timer");
                    SleepTimer.startAuto(PlaybackService.this, IDLE_GUARD_TIMER_MIN, "3 小时没有操作，已自动开 30 分钟睡眠定时");
                    lastInputAt = now;   // once; the timer (and its fade) takes it from here
                }
            } catch (Throwable t) {
                Diag.log("idle", String.valueOf(t));
            }
            MAIN.postDelayed(this, IDLE_CHECK_MS);
        }
    };

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
        MAIN.removeCallbacks(idleDetach);
        MAIN.removeCallbacks(longBufferRun);
        MAIN.removeCallbacks(clickDone);
        MAIN.removeCallbacks(idleGuardRun);
        MAIN.removeCallbacks(freezeRun);
        if (bgBuffering > 0) Diag.log("net", "buffering with the screen off this session: " + bgBuffering);
        bgBuffering = 0;
        releaseLocks();
        try { if (noisyReceiver != null) unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        try {
            if (netCallback != null) {
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
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, getString(R.string.n_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private void initSession() {
        session = new MediaSession(this, "YiWei");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { cmd(PlaybackService.this, "ytl.play()"); }
            @Override public void onPause() { cmd(PlaybackService.this, "ytl.pause()"); }
            @Override public void onStop() { cmd(PlaybackService.this, "ytl.pause()"); }
            @Override public void onSkipToNext() { cmd(PlaybackService.this, "ytl.next()"); }
            @Override public void onSkipToPrevious() { cmd(PlaybackService.this, "ytl.prev()"); }
            @Override public void onSeekTo(long pos) { cmd(PlaybackService.this, "ytl.seek(" + pos + ")"); }
            @Override public void onRewind() { cmd(PlaybackService.this, "ytl.seekBy(-10)"); }
            @Override public void onFastForward() { cmd(PlaybackService.this, "ytl.seekBy(30)"); }
            @Override public void onCustomAction(String action, android.os.Bundle extras) {
                noteInput();
                if (ACTION_SLEEP.equals(action)) SleepTimer.cycle(PlaybackService.this);
                else if (ACTION_EXTEND.equals(action)) SleepTimer.extend(PlaybackService.this);
                else if (ACTION_REW.equals(action)) cmd(PlaybackService.this, "ytl.seekBy(-10)");
                else if (ACTION_FWD.equals(action)) cmd(PlaybackService.this, "ytl.seekBy(30)");
                else if (ACTION_WL.equals(action)) cmd(PlaybackService.this, "ytl.watchLater()");
            }
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                if (handleMultiClick(mediaButtonIntent)) return true;
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 10, open, piFlags()));
        session.setActive(true);
    }

    // ---- headset button: 1 press play/pause, 2 next, 3 previous (24) ----
    private int clicks = 0;
    private final Runnable clickDone = () -> {
        int n = clicks;
        clicks = 0;
        if (n >= 3) cmd(this, "ytl.prev()");
        else if (n == 2) cmd(this, "ytl.next()");
        else if (n == 1) cmd(this, state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ? "ytl.pause()" : "ytl.play()");
    };

    private boolean handleMultiClick(Intent intent) {
        try {
            if (!Prefs.on(this, Prefs.HEADSET_MULTI) || intent == null) return false;
            android.view.KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (ev == null) return false;
            int code = ev.getKeyCode();
            if (code != android.view.KeyEvent.KEYCODE_HEADSETHOOK && code != android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false;
            if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                clicks++;
                MAIN.removeCallbacks(clickDone);
                MAIN.postDelayed(clickDone, MULTI_CLICK_MS);
            }
            return true; // swallow DOWN repeats and UP as well
        } catch (Exception e) {
            return false;
        }
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
        // the Wi-Fi saver experiment (271) is gone (473): the lock is always held while playing, so a night stream never drops
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
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    MAIN.postDelayed(() -> {
                        if (longBuffer) {
                            // network is back after a long stall (272): locks again, then resume
                            longBuffer = false;
                            Diag.log("net", "back after long buffering");
                            if (isPlaying()) acquireLocks();
                            update();
                        }
                        if (netNote) { netNote = false; update(); }
                        // a new default network (Wi-Fi <-> mobile data): "Wi-Fi best, mobile 720p" is applied again (314)
                        if (MainActivity.hasPage()) MainActivity.js("ytl.onNet();ytl.netChanged&&ytl.netChanged()");
                    }, 1500);
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
    }

    // what was last handed to the session / notification (263): unchanged values are not re-sent
    private String lastMetaKey = "";
    private String lastStateKey = "";
    private String lastNotifKey = "";
    private long bufferingSince = 0;

    /** Re-render session + notification from the static state; only the parts that changed (263). */
    private void update() {
        boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        boolean hasArt = art != null && artUrl.equals(artLoadedFor);

        String metaKey = title + "\u0001" + artist + "\u0001" + durationMs + "\u0001" + (hasArt ? artLoadedFor : "") + "\u0001" + videoId + "\u0001" + live;
        if (!metaKey.equals(lastMetaKey)) {
            lastMetaKey = metaKey;
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist);   // (295)
            if (!live) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);   // live (294): no length, no seek bar
            if (!videoId.isEmpty()) mb.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, videoId);
            if (hasArt) {
                mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
                mb.putBitmap(MediaMetadata.METADATA_KEY_ART, art); // lock screen (117)
            }
            session.setMetadata(mb.build());
        }

        boolean seekButtons = Prefs.on(this, Prefs.NOTIF_SEEK);
        boolean wl = Prefs.on(this, Prefs.NOTIF_WL) && !SleepTimer.timed() && MainActivity.signedIn();   // (346)
        String sleepLabel = SleepTimer.buttonLabel();
        String stateKey = state + "|" + positionMs + "|" + rate + "|" + positionAt + "|" + sleepLabel + "|" + SleepTimer.timed()
                + "|" + seekButtons + "|" + wl + "|" + live + "|" + SleepTimer.extendMin(this);
        if (!stateKey.equals(lastStateKey)) {
            lastStateKey = stateKey;
            session.setPlaybackState(buildState(seekButtons, wl, sleepLabel));
        }

        // long buffering (272): after 10 minutes stop holding the CPU / Wi-Fi awake
        if (state == PlaybackState.STATE_BUFFERING) {
            if (bufferingSince == 0) {
                bufferingSince = SystemClock.elapsedRealtime();
                MAIN.removeCallbacks(longBufferRun);
                MAIN.postDelayed(longBufferRun, LONG_BUFFER_MS);
            }
        } else {
            bufferingSince = 0;
            MAIN.removeCallbacks(longBufferRun);
        }

        if (playing) {
            MAIN.removeCallbacks(idleStop);
            MAIN.removeCallbacks(idleDetach);
            if (longBuffer) releaseLocks(); else acquireLocks();
            if (!foreground) { lastNotifKey = ""; goForeground(notification()); }
            else notifyIfChanged();
        } else {
            releaseLocks();
            // dismissible while paused in the app, once the sleep timer has stopped playback,
            // and when the system took the activity away (243)
            if (foreground && (MainActivity.isVisible() || sleepDone || interrupted)) stopFg(false);
            notifyIfChanged();
            MAIN.removeCallbacks(idleStop);
            MAIN.postDelayed(idleStop, sleepDone ? SLEEP_DONE_STOP_MS : IDLE_STOP_MS);
            MAIN.removeCallbacks(idleDetach);
            if (foreground) MAIN.postDelayed(idleDetach, IDLE_DETACH_MS);
        }
    }

    private String notifKey() {
        return title + "|" + artist + "|" + isPlaying() + "|" + SleepTimer.buttonLabel() + "|" + SleepTimer.timed() + "|"
                + Prefs.on(this, Prefs.NOTIF_SEEK) + "|" + Prefs.on(this, Prefs.NOTIF_WL) + "|" + MainActivity.signedIn() + "|" + SleepTimer.extendMin(this)
                + "|" + (art != null && artUrl.equals(artLoadedFor) ? artLoadedFor : "")
                + "|" + interrupted + "|" + longBuffer + "|" + netNote + "|" + foreground;
    }

    private Notification notification() {
        lastNotifKey = notifKey();
        return buildNotification();
    }

    private void notifyIfChanged() {
        String k = notifKey();
        if (k.equals(lastNotifKey)) return;
        lastNotifKey = k;
        try { nm.notify(NOTIFICATION_ID, buildNotification()); } catch (Exception ignored) {}
    }

    /**
     * Media session buttons (289). Android 13+ (ColorOS media card, lock screen) draws its buttons
     * from the PlaybackState, not from the notification: with "-10 s / +30 s" the skip actions are
     * left out so their slots take the custom rewind / forward actions, and "watch later" becomes
     * a custom action too. Older systems keep the notification buttons as before.
     */
    private PlaybackState buildState(boolean seekButtons, boolean wl, String sleepLabel) {
        boolean cards = Build.VERSION.SDK_INT >= 33;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD;
        if (!live) actions |= PlaybackState.ACTION_SEEK_TO;   // dragging a live stream does nothing (294)
        if (!(cards && seekButtons)) actions |= PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        PlaybackState.Builder pb = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state == PlaybackState.STATE_NONE ? PlaybackState.STATE_PAUSED : state,
                        positionMs, state == PlaybackState.STATE_PLAYING ? rate : 0f, positionAt);
        if (cards && seekButtons) {
            pb.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION_REW, getString(R.string.n_rew), R.drawable.ic_replay_10_white).build());
            pb.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION_FWD, getString(R.string.n_fwd), R.drawable.ic_forward_30_white).build());
        }
        pb.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION_SLEEP, sleepLabel, R.drawable.ic_timer_white).build());
        if (SleepTimer.timed()) {
            pb.addCustomAction(new PlaybackState.CustomAction.Builder(
                    ACTION_EXTEND, getString(R.string.n_extend, SleepTimer.extendMin(this)), R.drawable.ic_add_time_white).build());
        } else if (cards && wl) {
            pb.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION_WL, getString(R.string.n_wl), R.drawable.ic_watch_later_white).build());
        }
        return pb.build();
    }

    private final Runnable longBufferRun = () -> {
        if (state != PlaybackState.STATE_BUFFERING) return;
        longBuffer = true;
        Diag.log("net", "buffering > 10 min: locks released");
        releaseLocks();
        update();
    };

    private final Runnable idleStop = () -> {
        if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_BUFFERING) stopSelfNow();
    };

    private final Runnable idleDetach = () -> {
        if (!isPlaying() && foreground) {
            stopFg(false);
            lastNotifKey = "";
            notifyIfChanged();
        }
    };

    private void goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION_ID, n);
            foreground = true;
        } catch (Exception e) {
            // e.g. ForegroundServiceStartNotAllowedException when resumed from the background (291):
            // remember it, retried when the app comes to the front, shown in the keep-alive card
            long now = System.currentTimeMillis();
            if (now - lastDeniedLog > 10 * 60_000L) {   // once per 10 minutes, not on every update
                lastDeniedLog = now;
                Diag.log("fgs-denied", String.valueOf(e));
                try { Prefs.put(this, Prefs.FGS_DENIED_AT, now); } catch (Exception ignored) {}
            }
            try { nm.notify(NOTIFICATION_ID, n); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private void stopFg(boolean remove) {
        if (!foreground) return;
        stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        foreground = false;
        lastNotifKey = "";
    }

    private static int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, NotificationActionReceiver.class).setAction(a);
        return PendingIntent.getBroadcast(this, code, i, piFlags());
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);

        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (interrupted) open.putExtra(EXTRA_RESUME, true);
        String sleep = SleepTimer.status();
        String text = interrupted ? getString(R.string.n_interrupted) : (longBuffer || netNote) ? getString(R.string.n_net) : artist;

        b.setSmallIcon(R.drawable.ic_stat_yiwei)
                .setContentTitle(title.isEmpty() ? getString(R.string.app_name) : title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, interrupted ? 12 : 11, open, piFlags()))
                .setDeleteIntent(action(ACTION_DISMISS, 13))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(playing)
                .setOnlyAlertOnce(true);
        boolean seekButtons = Prefs.on(this, Prefs.NOTIF_SEEK);
        // labels from the string resources (380)
        b.addAction(seekButtons
                        ? new Notification.Action.Builder(R.drawable.ic_replay_10_white, getString(R.string.n_rew), action(ACTION_REW, 6)).build()
                        : new Notification.Action.Builder(R.drawable.ic_skip_previous_white, getString(R.string.n_prev), action(ACTION_PREV, 1)).build())
                .addAction(playing
                        ? new Notification.Action.Builder(R.drawable.ic_pause_white, getString(R.string.n_pause), action(ACTION_PAUSE, 2)).build()
                        : new Notification.Action.Builder(R.drawable.ic_play_arrow_white, getString(R.string.n_play), action(ACTION_PLAY, 3)).build())
                .addAction(seekButtons
                        ? new Notification.Action.Builder(R.drawable.ic_forward_30_white, getString(R.string.n_fwd), action(ACTION_FWD, 7)).build()
                        : new Notification.Action.Builder(R.drawable.ic_skip_next_white, getString(R.string.n_next), action(ACTION_NEXT, 4)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_timer_white,
                        SleepTimer.buttonLabel(), action(ACTION_SLEEP, 5)).build());
        if (!playing && !SleepTimer.timed()) {
            // paused: the fifth button closes playback and the notification (290)
            b.addAction(new Notification.Action.Builder(R.drawable.ic_close_white, getString(R.string.n_close), action(ACTION_CLOSE, 14)).build());
        } else if (SleepTimer.timed()) {
            // half asleep: one more quarter hour without turning the screen on (14)
            b.addAction(new Notification.Action.Builder(R.drawable.ic_add_time_white,
                    getString(R.string.n_extend, SleepTimer.extendMin(this)), action(ACTION_EXTEND, 8)).build());
        } else if (Prefs.on(this, Prefs.NOTIF_WL) && MainActivity.signedIn()) {
            // heard something good: save it for later (100); MediaStyle allows five actions; signed in only (346)
            b.addAction(new Notification.Action.Builder(R.drawable.ic_watch_later_white, getString(R.string.n_wl), action(ACTION_WL, 9)).build());
        }
        b.setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (sleep != null) b.setSubText(sleep);
        if (art != null && artUrl.equals(artLoadedFor)) b.setLargeIcon(art);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
