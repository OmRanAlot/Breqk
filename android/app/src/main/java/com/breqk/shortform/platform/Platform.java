package com.breqk.shortform.platform;

/**
 * Enum of all platforms for which short-form content monitoring is defined.
 * Add new platforms here first, then create the corresponding *ViewIds,
 * *Detector, and *FilterHandler files, and register in PlatformRegistry.
 */
public enum Platform {
    INSTAGRAM("com.instagram.android"),
    YOUTUBE("com.google.android.youtube"),
    TIKTOK("com.zhiliaoapp.musically"),
    FACEBOOK("com.facebook.katana"),
    SNAPCHAT("com.snapchat.android");

    public final String pkg;

    Platform(String pkg) {
        this.pkg = pkg;
    }

    /** Returns the Platform for a package name, or null if unrecognised. */
    public static Platform fromPackage(String pkg) {
        for (Platform p : values()) {
            if (p.pkg.equals(pkg)) return p;
        }
        return null;
    }
}
