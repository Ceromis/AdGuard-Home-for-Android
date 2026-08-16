package com.adguardhome.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps the timed protection pause visible as a SystemUI countdown. */
public final class PauseCountdownService extends Service {
    private static final String CHANNEL_ID = "protection_pause_countdown";
    private static final int NOTIFICATION_ID = 2201;
    private static final String PREFS = "manager_state";
    private static final String PREF_PAUSE_UNTIL = "pause_until";
    private static final String ACTION_START = "com.adguardhome.android.action.START_PAUSE_COUNTDOWN";
    private static final String ACTION_RESUME = "com.adguardhome.android.action.RESUME_PROTECTION";
    private static final String ACTION_CANCEL = "com.adguardhome.android.action.CANCEL_PAUSE_COUNTDOWN";
    private static final String EXTRA_DEADLINE = "deadline";
    private static final String TAG = "PauseCountdown";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private long deadline;
    private boolean resumeInFlight;
    private boolean promotedCountdown;
    private final Runnable promotedCountdownUpdater = new Runnable() {
        @Override
        public void run() {
            if (!promotedCountdown || resumeInFlight || deadline <= System.currentTimeMillis()) return;
            getSystemService(NotificationManager.class).notify(
                    NOTIFICATION_ID, buildNotification(true, null));
            handler.postDelayed(this, 1_000L);
        }
    };

    public static void start(Context context, long deadline) {
        if (deadline <= System.currentTimeMillis()) {
            cancel(context);
            return;
        }
        Intent intent = new Intent(context, PauseCountdownService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEADLINE, deadline);
        context.startForegroundService(intent);
    }

    public static void restore(Context context) {
        long saved = context.getSharedPreferences(PREFS, MODE_PRIVATE).getLong(PREF_PAUSE_UNTIL, 0L);
        if (saved > System.currentTimeMillis() && saved < Long.MAX_VALUE) start(context, saved);
    }

    public static void cancel(Context context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(PREF_PAUSE_UNTIL, 0L).apply();
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        context.stopService(new Intent(context, PauseCountdownService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            finishCountdown();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            long saved = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(PREF_PAUSE_UNTIL, 0L);
            deadline = saved > 0L ? saved : System.currentTimeMillis();
            startForeground(NOTIFICATION_ID, buildNotification(false, "正在恢复保护"));
            resumeProtection();
            return START_NOT_STICKY;
        }

        long requested = intent == null ? 0L : intent.getLongExtra(EXTRA_DEADLINE, 0L);
        deadline = requested > 0L ? requested
                : getSharedPreferences(PREFS, MODE_PRIVATE).getLong(PREF_PAUSE_UNTIL, 0L);
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0L || deadline == Long.MAX_VALUE) {
            finishCountdown();
            return START_NOT_STICKY;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(PREF_PAUSE_UNTIL, deadline).apply();
        startForeground(NOTIFICATION_ID, buildNotification(true, null));
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::resumeProtection, remaining);
        if (promotedCountdown) handler.postDelayed(promotedCountdownUpdater, 1_000L);
        return START_STICKY;
    }

    private Notification buildNotification(boolean countdown, String status) {
        Intent open = new Intent(this, ManagerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent resume = new Intent(this, PauseCountdownService.class).setAction(ACTION_RESUME);
        PendingIntent resumeIntent = PendingIntent.getService(this, 1, resume,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pause_timer)
                .setColor(Color.rgb(0, 103, 192))
                .setContentTitle("广告过滤已暂停")
                .setContentText(status == null ? "倒计时结束后自动恢复保护" : status)
                .setSubText("AdGuard Home")
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_ALARM)
                .addAction(new Notification.Action.Builder(null, "立即恢复", resumeIntent).build());
        if (countdown) {
            builder.setWhen(deadline)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setTimeoutAfter(Math.max(1_000L, deadline - System.currentTimeMillis() + 30_000L));
            requestPromotedCountdown(builder);
        }
        return builder.build();
    }

    private void requestPromotedCountdown(Notification.Builder builder) {
        if (android.os.Build.VERSION.SDK_INT < 36) return;
        try {
            Notification.Builder.class
                    .getMethod("setRequestPromotedOngoing", boolean.class)
                    .invoke(builder, true);
            Notification.Builder.class
                    .getMethod("setShortCriticalText", CharSequence.class)
                    .invoke(builder, formatRemaining(deadline - System.currentTimeMillis()));
            promotedCountdown = true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            promotedCountdown = false;
            Log.d(TAG, "Promoted countdown is unavailable on this Android build", error);
        }
    }

    private static String formatRemaining(long millis) {
        long seconds = Math.max(1L, (millis + 999L) / 1_000L);
        long hours = seconds / 3_600L;
        long minutes = seconds % 3_600L / 60L;
        long rest = seconds % 60L;
        return hours > 0L
                ? String.format(Locale.CHINA, "%02d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.CHINA, "%02d:%02d", minutes, rest);
    }

    private void resumeProtection() {
        if (resumeInFlight) return;
        resumeInFlight = true;
        handler.removeCallbacksAndMessages(null);
        getSystemService(NotificationManager.class).notify(
                NOTIFICATION_ID, buildNotification(false, "正在恢复保护"));
        executor.execute(() -> {
            try {
                new ApiClient().post("/control/protection",
                        new JSONObject().put("enabled", true).put("duration", 0L));
                handler.post(this::finishCountdown);
            } catch (Exception error) {
                handler.post(() -> {
                    resumeInFlight = false;
                    getSystemService(NotificationManager.class).notify(
                            NOTIFICATION_ID, buildNotification(false, "自动恢复失败，点击“立即恢复”重试"));
                });
            }
        });
    }

    private void finishCountdown() {
        handler.removeCallbacksAndMessages(null);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(PREF_PAUSE_UNTIL, 0L).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "保护暂停倒计时", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("显示广告过滤恢复前的剩余时间");
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
