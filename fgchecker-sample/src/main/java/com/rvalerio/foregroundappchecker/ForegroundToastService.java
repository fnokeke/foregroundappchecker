package com.rvalerio.foregroundappchecker;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Vibrator;
import android.support.annotation.Nullable;
import android.support.v7.app.NotificationCompat;
import android.util.Log;

import com.android.volley.VolleyError;
import com.rvalerio.fgchecker.AppChecker;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import io.smalldata.api.CallAPI;
import io.smalldata.api.VolleyJsonCallback;

import static com.rvalerio.foregroundappchecker.Helper.getStoreBoolean;
import static com.rvalerio.foregroundappchecker.Helper.getStoreInt;
import static com.rvalerio.foregroundappchecker.Helper.getStoreString;
import static com.rvalerio.foregroundappchecker.Helper.increaseStoreInt;
import static com.rvalerio.foregroundappchecker.Helper.setStoreBoolean;
import static com.rvalerio.foregroundappchecker.Helper.setStoreInt;
import static com.rvalerio.foregroundappchecker.Helper.setStoreString;


public class ForegroundToastService extends Service {

    private final static int NOTIFICATION_ID = 1234;
    private final static String STOP_SERVICE = ForegroundToastService.class.getPackage() + ".stop";

    private final Locale locale = Locale.getDefault();
    private Context mContext;

    private BroadcastReceiver appCheckerReceiver;
    private BroadcastReceiver networkConnReceiver;
    private BroadcastReceiver screenUnlockReceiver;

    private AppChecker appChecker;

    final private Integer FB_MAX_DAILY_MINUTES = 10;
    final private Integer FB_MAX_DAILY_OPENS = 2;

    private Integer fbTimeSpent;
    private Integer fbNumOfOpens;

    private final static long UPDATE_SERVER_INTERVAL_MS = 2 * 3600 * 1000;

    private NotificationCompat.Builder mBuilder;
    private NotificationManager mNotificationManager;
    private boolean firstTime = true;
    private Handler serverHandler = new Handler();

    private final static int BASELINE_ENDS = 7;
    private final static int FOLLOWUP_BEGINS = 15;
    private final static int EXPERIMENT_ENDS = 21;

    private static String TAG = "ForegroundToastService";

    public static void start(Context context) {
        context.startService(new Intent(context, ForegroundToastService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ForegroundToastService.class));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mContext = this;

        registerStopCheckerReceiver();
        registerScreenUnlockReceiver();
        registerNetworkMonitorReceiver();

        startChecker();

        mBuilder = new NotificationCompat.Builder(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        updateNotification("Daily stats will update throughout day.");
        serverHandler.postDelayed(serverUpdateTask, 0);
    }

    private Runnable serverUpdateTask = new Runnable() {
        public void run() {
            updateServerRecords(getStoreInt(mContext, "fbTimeSpent"), getStoreInt(mContext, "fbNumOfOpens"));
            serverHandler.postDelayed(this, UPDATE_SERVER_INTERVAL_MS);
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopChecker();
        removeNotification();
        unregisterAllReceivers();
        stopSelf();
    }

    public Boolean isLockedScreen() {
        KeyguardManager myKM = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return myKM.inKeyguardRestrictedInputMode();
    }

    private void updateLastDate() {

        if (isNewDay() && isPast3am()) {
            Integer todayTimeSpent = getStoreInt(mContext, "fbTimeSpent");
            Integer todayNumOpens = getStoreInt(mContext, "fbNumOfOpens");
            updateServerRecords(todayTimeSpent, todayNumOpens);

            setStoreString(mContext, "lastRecordedDate", Helper.getTodayDateStr());
            increaseStoreInt(mContext, "studyDayCount", 1);
            setStoreInt(mContext, "fbTimeSpent", 0);
            setStoreInt(mContext, "fbNumOfOpens", 0);
            setStoreInt(mContext, "totalSeconds", 0);
            setStoreInt(mContext, "totalOpens", 0);
            setStoreBoolean(mContext, "serverUpdatedToday", false);
        }
    }

    private boolean isNewDay() {
        String lastRecordeddDate = getStoreString(mContext, "lastRecordedDate");
        String today = new SimpleDateFormat("yyyy-MM-dd", locale).format(Calendar.getInstance().getTime());
        return !lastRecordeddDate.equals(today);
    }

    private boolean isPast3am() {
        Calendar rightNow = Calendar.getInstance();
        Integer hour24 = rightNow.get(Calendar.HOUR_OF_DAY);
        return hour24 >= 3;
    }

    private void startChecker() {

        appChecker = new AppChecker();
        appChecker.when("com.facebook.katana", new AppChecker.Listener() {
            @Override
            public void onForeground(String packageName) {
                if (isLockedScreen()) return;

                increaseStoreInt(mContext, "totalSeconds", 5);
                increaseStoreInt(mContext, "fbTimeSpent", 5);

                if (getStoreBoolean(mContext, "fbVisitedAnotherApp")) {
                    increaseStoreInt(mContext, "fbNumOfOpens", 1);
                    setStoreBoolean(mContext, "fbVisitedAnotherApp", false);
                }

                fbTimeSpent = getStoreInt(mContext, "fbTimeSpent");
                fbNumOfOpens = getStoreInt(mContext, "fbNumOfOpens");
                updateNotification(getCurrentStats());
                updateLastDate();

                if (fbTimeSpent > FB_MAX_DAILY_MINUTES * 60 || fbNumOfOpens > FB_MAX_DAILY_OPENS) {
                    String info = String.format(locale, "Facebook: %d secs (%dx)", fbTimeSpent, fbNumOfOpens);

                    // vibration should only happen during treatment/intervention period
                    // and only participants in group 2 should experience vibration as group 1 is control group
                    if (!isTreatmentPeriod()) return;
                    if (!shouldAllowVibration()) return;
                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    long[] pattern = {0, 100, 100, 100, 100};
                    v.vibrate(pattern, -1);
                }

            }

        }).other(new AppChecker.Listener() {
            @Override
            public void onForeground(String packageName) {
                if (isLockedScreen()) return;

                increaseStoreInt(mContext, "totalSeconds", 5);
                setStoreBoolean(mContext, "fbVisitedAnotherApp", true);

                updateNotification(getCurrentStats());
                updateLastDate();

            }

        }).timeout(5000).start(this);
    }

    private boolean isTreatmentPeriod() {
        Integer studyDayCount = getStoreInt(mContext, "studyDayCount");
        return studyDayCount > BASELINE_ENDS && studyDayCount < FOLLOWUP_BEGINS;
    }

    private boolean shouldAllowVibration() {
        return getStoreInt(mContext, "experimentGroup") == 2;
    }

    private boolean studyIsOver() {
        Integer studyDayCount = getStoreInt(mContext, "studyDayCount");
        return studyDayCount > EXPERIMENT_ENDS;
    }


    // only update server records when study is still ongoing
    private void updateServerRecords(Integer timeSpent, Integer timeOpen) {
        if (studyIsOver()) return;
        JSONObject params = new JSONObject();
        Helper.setJSONValue(params, "device_id", DeviceInfo.getDeviceID(mContext));
        Helper.setJSONValue(params, "worker_id", getStoreString(mContext, "workerID"));
        Helper.setJSONValue(params, "total_seconds", getStoreInt(mContext, "totalSeconds"));
        Helper.setJSONValue(params, "total_opens", getStoreInt(mContext, "totalOpens"));
        Helper.setJSONValue(params, "time_spent", timeSpent);
        Helper.setJSONValue(params, "time_open", timeOpen);
        Helper.setJSONValue(params, "ringer_mode", DeviceInfo.getRingerMode(mContext));
        Log.d("updateServerParams", params.toString() + timeSpent.toString() + timeOpen.toString());
        CallAPI.submitFBStats(mContext, params, submitStatsResponseHandler);
    }

    VolleyJsonCallback submitStatsResponseHandler = new VolleyJsonCallback() {
        @Override
        public void onConnectSuccess(JSONObject result) {
            Log.i(TAG, "Submit Stats: " + result.toString());
            setStoreBoolean(mContext, "serverUpdatedToday", true);
        }

        @Override
        public void onConnectFailure(VolleyError error) {
            String msg = "Stats submit error: " + error.toString();
            Log.e(TAG, "StatsError: " + msg);
        }

    };

    private String getCurrentStats() {
        Integer fbTimeSpent = getStoreInt(mContext, "fbTimeSpent");
        Integer fbNumOfOpens = getStoreInt(mContext, "fbNumOfOpens");

        return String.format("Facebook: %s secs (%sx)",
                fbTimeSpent.toString(),
                fbNumOfOpens.toString());
    }

    private void stopChecker() {
        appChecker.stop();
    }

    private void registerStopCheckerReceiver() {
        appCheckerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopSelf();
            }
        };
        registerReceiver(appCheckerReceiver, new IntentFilter(STOP_SERVICE));
    }

    private void registerNetworkMonitorReceiver() {
        networkConnReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle extras = intent.getExtras();
                NetworkInfo info = extras.getParcelable("networkInfo");
                NetworkInfo.State state = null;
                if (info != null) {
                    state = info.getState();
                }

                Boolean serverIsNotYetUpdated = !getStoreBoolean(mContext, "serverUpdatedToday");
                if (state == NetworkInfo.State.CONNECTED && serverIsNotYetUpdated) {
                    updateServerRecords(getStoreInt(mContext, "fbTimeSpent"), getStoreInt(mContext, "fbNumOfOpens"));
                }
            }
        };

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkConnReceiver, intentFilter);
    }

    private void registerScreenUnlockReceiver() {
        screenUnlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                increaseStoreInt(mContext, "totalOpens", 1);
            }
        };
        registerReceiver(screenUnlockReceiver, new IntentFilter("android.intent.action.USER_PRESENT"));
    }

    private void unregisterAllReceivers() {
        unregisterReceiver(networkConnReceiver);
        unregisterReceiver(screenUnlockReceiver);
        unregisterReceiver(appCheckerReceiver);
    }

    private void updateNotification(String message) {
        String title = "Recent usage stats";

        if (firstTime) {
            mBuilder
                    .setSmallIcon(R.drawable.ic_chart_pink)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setOngoing(true);
            firstTime = false;
        }

        mBuilder.setContentTitle(title);
        mBuilder.setContentText(message);
        mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
    }

    private void removeNotification() {
        NotificationManager manager = ((NotificationManager) getSystemService(NOTIFICATION_SERVICE));
        manager.cancel(NOTIFICATION_ID);
    }

//    private Notification createStickyNotification() {
//        NotificationManager manager = ((NotificationManager) getSystemService(NOTIFICATION_SERVICE));
//        Notification notification = new NotificationCompat.Builder(this).setSmallIcon(R.mipmap.ic_launcher)
//                .setPriority(NotificationCompat.PRIORITY_MIN)
//                .setOngoing(true).setOnlyAlertOnce(true)
//                .setAutoCancel(false)
//                .setContentTitle(getString(R.string.app_name))
//                .setContentText(getString(R.string.stop_service))
//                .setContentIntent(PendingIntent.getBroadcast(this, 0,
//                        new Intent(STOP_SERVICE), PendingIntent.FLAG_UPDATE_CURRENT))
//                .setWhen(0).build();
//
//        manager.notify(NOTIFICATION_ID, notification);
//        return notification;
//    }


}

// TODO: 3/4/17 add totalOpens
// TODO: 3/4/17 add deviceInfo to server through Helper class
