package com.wordweave.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONObject;
import java.util.Calendar;

/** Optional, inexact daily local reminder. It never contacts the AI API. */
public class ReviewReminder extends BroadcastReceiver {
    private static final String CHANNEL = "wordweave_review";
    private static final String ACTION = "com.wordweave.app.REVIEW_REMINDER";

    public static void schedule(Context context, JSONObject prefs) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent alarm = PendingIntent.getBroadcast(context, 820,
                new Intent(context, ReviewReminder.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.cancel(alarm);
        if (!prefs.optBoolean("reminder", false)) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, prefs.optInt("reminderHour", 20));
        next.set(Calendar.MINUTE, prefs.optInt("reminderMinute", 0));
        next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DATE, 1);
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), alarm);
    }

    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        new Thread(() -> {
            WordRepository db = null;
            try {
                JSONObject prefs = new SecureSettings(context).loadPublic();
                schedule(context, prefs);
                if (!ACTION.equals(intent.getAction()) || !prefs.optBoolean("reminder", false)) return;
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return;
                db = new WordRepository(context);
                int due = db.dashboard().optInt("dueWords", 0);
                if (due == 0) return;
                NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                manager.createNotificationChannel(new NotificationChannel(CHANNEL, "单词复习", NotificationManager.IMPORTANCE_DEFAULT));
                PendingIntent launch = PendingIntent.getActivity(context, 821,
                        new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification notification = new Notification.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_launcher).setContentTitle("词链 · 今天的复习")
                        .setContentText(due + " 个词已到复习时间，花几分钟回忆一下。")
                        .setContentIntent(launch).setAutoCancel(true).build();
                manager.notify(822, notification);
            } catch (Exception ignored) {
                // A reminder failure must not affect the stored learning progress.
            } finally {
                if (db != null) db.close();
                pending.finish();
            }
        }, "wordweave-reminder").start();
    }
}
