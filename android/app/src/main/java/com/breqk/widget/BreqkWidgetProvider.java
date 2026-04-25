package com.breqk.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.RemoteViews;

import com.breqk.MainActivity;
import com.breqk.R;

/**
 * BreqkWidgetProvider — Home screen widget controller.
 *
 * PURPOSE:
 *   Provides a simple home screen widget with two tappable icons:
 *     - Instagram icon → opens distraction-free Instagram browser
 *     - YouTube icon   → opens distraction-free YouTube browser
 *   Tapping the app title opens the main Breqk app.
 *
 * HOW IT WORKS:
 *   1. Android calls onUpdate() when the widget is first placed or periodically refreshed.
 *   2. We inflate the layout (R.layout.widget_breqk) via RemoteViews.
 *   3. We create PendingIntents for each clickable element:
 *      - Title → launches MainActivity (the main app)
 *      - Instagram icon → deep links to breqk://browser/instagram
 *      - YouTube icon   → deep links to breqk://browser/youtube
 *   4. Deep links are handled by React Navigation's linking config in App.tsx,
 *      which routes to BrowserScreen.js with the correct platform parameter.
 *
 * RELATED FILES:
 *   - res/layout/widget_breqk.xml            → Widget UI layout
 *   - res/xml/widget_breqk_info.xml           → Widget metadata (size, update interval)
 *   - res/drawable/ic_instagram.xml           → Instagram icon (teal vector drawable)
 *   - res/drawable/ic_youtube.xml             → YouTube icon (teal vector drawable)
 *   - AndroidManifest.xml                     → Widget <receiver> registration + deep link <intent-filter>
 *   - App.tsx                                 → React Navigation linking config for deep links
 *   - components/Browser/BrowserScreen.js     → The distraction-free browser that opens
 *
 * LOGGING:
 *   Tag: "BreqkWidget"
 *   Filter with: adb logcat -s BreqkWidget
 */
public class BreqkWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "BreqkWidget";

    /**
     * Called by the Android system when widget(s) need to be updated.
     * This happens when the widget is first placed on the home screen,
     * and periodically based on updatePeriodMillis in widget_breqk_info.xml (30 min).
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Log.d(TAG, "onUpdate: updating " + appWidgetIds.length + " widget instance(s)");

        // FLAG_ACTIVITY_NEW_TASK required when starting activity from non-activity context (widget)
        // FLAG_ACTIVITY_CLEAR_TOP brings app to front if already open
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // FLAG_IMMUTABLE required for PendingIntents targeting API 31+ (target is 35)
        PendingIntent launchPending = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Log.d(TAG, "onUpdate: main app launch PendingIntent created");

        // breqk://browser/instagram is caught by MainActivity's intent-filter in AndroidManifest.xml,
        // then React Navigation routes it to BrowserScreen with platform='instagram'.
        Intent instagramIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("breqk://browser/instagram"));
        instagramIntent.setPackage(context.getPackageName());
        instagramIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Request code 1 — must be unique per PendingIntent to avoid Android deduplication
        PendingIntent instagramPending = PendingIntent.getActivity(
                context, 1, instagramIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Log.d(TAG, "onUpdate: Instagram deep link PendingIntent created");

        // breqk://browser/youtube — same flow as Instagram, routes to BrowserScreen with platform='youtube'.
        Intent youtubeIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("breqk://browser/youtube"));
        youtubeIntent.setPackage(context.getPackageName());
        youtubeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Request code 2 — unique to differentiate from Instagram PendingIntent
        PendingIntent youtubePending = PendingIntent.getActivity(
                context, 2, youtubeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Log.d(TAG, "onUpdate: YouTube deep link PendingIntent created");

        // Users can place multiple copies of the same widget; each gets its own appWidgetId
        for (int appWidgetId : appWidgetIds) {
            Log.d(TAG, "onUpdate: configuring widget instance ID=" + appWidgetId);

            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_breqk);
            views.setOnClickPendingIntent(R.id.widget_root, launchPending);
            views.setOnClickPendingIntent(R.id.widget_title, launchPending);
            views.setOnClickPendingIntent(R.id.widget_btn_instagram, instagramPending);
            views.setOnClickPendingIntent(R.id.widget_btn_youtube, youtubePending);

            appWidgetManager.updateAppWidget(appWidgetId, views);
            Log.d(TAG, "onUpdate: widget instance ID=" + appWidgetId + " updated successfully");
        }
    }
}
