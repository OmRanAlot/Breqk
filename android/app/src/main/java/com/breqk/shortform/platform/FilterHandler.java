package com.breqk.shortform.platform;

import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Per-platform short-form content detection contract.
 *
 * Implement this interface for each supported platform and register the implementation
 * in PlatformRegistry. ContentFilter calls handle() and fires ejection if true is returned.
 *
 * To enable a new platform:
 *   1. Create *ViewIds, *Detector, and *FilterHandler files under platform/<name>/.
 *   2. Implement handle() and platform() below.
 *   3. Register in PlatformRegistry.HANDLERS.
 */
public interface FilterHandler {
    /**
     * Inspects the event and accessibility tree and returns true if the user is in
     * short-form content that should trigger ejection.
     *
     * @param event Accessibility event from AppEventRouter
     * @param root  Root AccessibilityNodeInfo for the current window; may be null
     * @return true if short-form content was confirmed; false otherwise
     */
    boolean handle(AccessibilityEvent event, AccessibilityNodeInfo root);

    /** Platform identity — used for logging and budget key lookups. */
    Platform platform();
}
