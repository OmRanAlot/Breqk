package com.breqk;

/*
 * MainActivity
 * ------------
 * Standard ReactActivity host. Handles optional VPN permission result
 * and starts the foreground service if granted.
 */

import com.facebook.react.ReactActivity;
import com.facebook.react.ReactActivityDelegate;
import com.facebook.react.ReactRootView;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import com.breqk.service.BreqkVpnService;
import com.breqk.monitor.ServiceHelper;

public class MainActivity extends ReactActivity {
    private static final int VPN_REQUEST_CODE = 1;

    /**
     * Returns the name of the main component registered from JavaScript.
     * This is used to schedule rendering of the component.
     */
    @Override
    protected String getMainComponentName() {
        return "Breqk"; // This should match the "name" in app.json
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // Permission granted, start the VPN service
                Intent intent = new Intent(this, BreqkVpnService.class);
                intent.setAction("START_VPN");
                ServiceHelper.startForegroundServiceCompat(this, intent);
            }
            // If result is not OK, the user denied the permission
        }
    }
}
