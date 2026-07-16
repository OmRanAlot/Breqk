package com.Break;

/*
 * MainActivity
 * ------------
 * Standard ReactActivity host. Launches the foreground monitoring service on start.
 */

import com.facebook.react.ReactActivity;
import com.facebook.react.ReactActivityDelegate;
import com.facebook.react.ReactRootView;
import android.content.Intent;
import android.os.Bundle;
import com.Break.mode.ModeManager;
import com.Break.service.BreakVpnService;
import com.Break.monitor.ServiceHelper;

public class MainActivity extends ReactActivity {

    /**
     * Returns the name of the main component registered from JavaScript.
     * This is used to schedule rendering of the component.
     */
    @Override
    protected String getMainComponentName() {
        return "Break"; // This should match the "name" in app.json
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Alarm-free schedule safety net: reconcile scheduled modes whenever the
        // user opens the app, so mode state is correct even if an AlarmManager
        // transition was deferred (Doze / no exact-alarm permission) or lost
        // (force-stop). Idempotent — only acts on an actual window transition.
        ModeManager.applyCurrentScheduleState(this);
    }
}
