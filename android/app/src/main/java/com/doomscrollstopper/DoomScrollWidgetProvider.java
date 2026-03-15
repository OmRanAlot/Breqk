package com.doomscrollstopper;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * DoomScrollWidgetProvider — Home screen widget controller.
 *
 * PURPOSE:
 *   Provides a simple home screen widget with two tappable icons:
 *     - Instagram icon → opens distraction-free Instagram browser
 *     - YouTube icon   → opens distraction-free YouTube browser
 *   Tapping the app title opens the main DoomScrollStopper app.
 *
 * HOW IT WORKS:
 *   1. Android calls onUpdate() when the widget is first placed or periodically refreshed.
 *   2. We inflate the layout (R.layout.widget_doomscroll) via RemoteViews.
 *   3. We create PendingIntents for each clickable element:
 *      - Title → launches MainActivity (the main app)
 *      - Instagram icon → deep links to doomscroll://browser/instagram
 *      - YouTube icon   → deep links to doomscroll://browser/youtube
 *   4. Deep links are handled by React Navigation's linking config in App.tsx,
 *      which routes to BrowserScreen.js with the correct platform parameter.
 *
 * RELATED FILES:
 *   - res/layout/widget_doomscroll.xml       → Widget UI layout
 *   - res/xml/widget_doomscroll_info.xml      → Widget metadata (size, update interval)
 *   - res/drawable/ic_instagram.xml           → Instagram icon (teal vector drawable)
 *   - res/drawable/ic_youtube.xml             → YouTube icon (teal vector drawable)
 *   - AndroidManifest.xml                     → Widget <receiver> registration + deep link <intent-filter>
 *   - App.tsx                                 → React Navigation linking config for deep links
 *   - components/Browser/BrowserScreen.js     → The distraction-free browser that opens
 *
 * LOGGING:
 *   Tag: "DoomScrollWidget"
 *   Filter with: adb logcat -s DoomScrollWidget
 */
public class DoomScrollWidgetProvider extends AppWidgetProvider {

    /** Log tag — filter with: adb logcat -s DoomScrollWidget */
    private static final String TAG = "DoomScrollWidget";

    /**
     * Called by the Android system when widget(s) need to be updated.
     * This happens when the widget is first placed on the home screen,
     * and periodically based on updatePeriodMillis in widget_doomscroll_info.xml (30 min).
     *
     * @param context          Application context for creating intents and accessing resources
     * @param appWidgetManager System service that manages widget updates
     * @param appWidgetIds     Array of widget instance IDs to update (user can place multiple widgets)
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Log.d(TAG, "onUpdate: updating " + appWidgetIds.length + " widget instance(s)");

        // --- Create PendingIntent for the app title (opens main app) ---
        // FLAG_ACTIVITY_NEW_TASK: required when starting activity from non-activity context (widget)
        // FLAG_ACTIVITY_CLEAR_TOP: if app is already open, bring it to front instead of creating new instance
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Request code 0 for the main app launch intent
        // FLAG_IMMUTABLE: required for PendingIntents targeting API 31+ (our target is 35)
        PendingIntent launchPending = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Log.d(TAG, "onUpdate: main app launch PendingIntent created");

        // --- Create PendingIntent for Instagram icon (opens distraction-free browser) ---
        // Deep link URI: doomscroll://browser/instagram
        // This is caught by the intent-filter in AndroidManifest.xml on MainActivity,
        // then React Navigation's linking config in App.tsx routes it to BrowserScreen
        // with platform='instagram', which loads instagram.com with Reels/Shorts CSS stripped.
        Intent instagramIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("doomscroll://browser/instagram"));
        instagramIntent.setPackage(context.getPackageName()); // Ensure only our app handles this
        instagramIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Request code 1 — must be unique per PendingIntent to avoid Android deduplication
        PendingIntent instagramPending = PendingIntent.getActivity(
                context, 1, instagramIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Log.d(TAG, "onUpdate: Instagram deep link PendingIntent created (doomscroll://browser/instagram)");

        // --- Create PendingIntent for YouTube icon (opens distraction-free browser) ---
        // Deep link URI: doomscroll://browser/youtube
        // Same flow as Instagram: AndroidManifest → React Navigation → BrowserScreen
        // with platform='youtube', which loads youtube.com with Shorts/recommendations stripped.
        Intent youtubeIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("doomscroll://browser/youtube"));
        youtubeIntent.setPackage(context.getPackageName()); // Ensure only our app handles this
        youtubeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Request code 2 — unique to differentiate from Instagram PendingIntent
        PendingIntent youtubePending = PendingIntent.getActivity(
                context, 2, youtubeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Log.d(TAG, "onUpdate: YouTube deep link PendingIntent created (doomscroll://browser/youtube)");

        // --- Apply PendingIntents to each widget instance ---
        // Users can place multiple copies of the same widget; each gets its own appWidgetId
        for (int appWidgetId : appWidgetIds) {
            Log.d(TAG, "onUpdate: configuring widget instance ID=" + appWidgetId);

            // Inflate the widget layout (res/layout/widget_doomscroll.xml)
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_doomscroll);

            // Wire up click handlers to each element
            views.setOnClickPendingIntent(R.id.widget_root, launchPending);           // Whole widget background → open app
            views.setOnClickPendingIntent(R.id.widget_title, launchPending);          // App title text → open app
            views.setOnClickPendingIntent(R.id.widget_btn_instagram, instagramPending); // Instagram icon → open browser
            views.setOnClickPendingIntent(R.id.widget_btn_youtube, youtubePending);     // YouTube icon → open browser

            // Push the updated RemoteViews to the home screen launcher
            appWidgetManager.updateAppWidget(appWidgetId, views);
            Log.d(TAG, "onUpdate: widget instance ID=" + appWidgetId + " updated successfully");
        }
    }
}
