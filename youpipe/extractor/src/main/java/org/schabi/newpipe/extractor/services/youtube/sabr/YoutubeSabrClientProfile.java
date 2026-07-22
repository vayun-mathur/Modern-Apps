package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public enum YoutubeSabrClientProfile {
    WEB("WEB", "1", "2.20250122.04.00", null, null, false, null),
    MWEB("MWEB", "2", "2.20250122.04.00", null, null, true,
            "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"),
    WEB_EMBEDDED("WEB_EMBEDDED_PLAYER", "56", "1.20250121.00.00", null, null, true, null),
    ANDROID("ANDROID", "3", "21.03.36", "Android", "16", false,
            "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip"),
    ANDROID_VR("ANDROID_VR", "28", "1.65.10", "Android", "12L", false,
            "com.google.android.apps.youtube.vr.oculus/1.65.10 "
                    + "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"),
    IOS("IOS", "5", "19.45.4", "iOS", "18.1.0.22B83", false,
            "com.google.ios.youtube/19.45.4(iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X; US)"),
    TVHTML5("TVHTML5", "7", "7.20250923.13.00", null, null, true,
            "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/15.4 Safari/605.1.15");

    @Nonnull
    private final String clientName;
    @Nonnull
    private final String clientId;
    @Nonnull
    private final String clientVersion;
    @Nullable
    private final String osName;
    @Nullable
    private final String osVersion;
    private final boolean webLike;
    @Nullable
    private final String userAgent;

    YoutubeSabrClientProfile(@Nonnull final String clientName,
                             @Nonnull final String clientId,
                             @Nonnull final String clientVersion,
                             @Nullable final String osName,
                             @Nullable final String osVersion,
                             final boolean webLike,
                             @Nullable final String userAgent) {
        this.clientName = clientName;
        this.clientId = clientId;
        this.clientVersion = clientVersion;
        this.osName = osName;
        this.osVersion = osVersion;
        this.webLike = webLike;
        this.userAgent = userAgent;
    }

    @Nonnull
    public String getClientName() {
        return clientName;
    }

    @Nonnull
    public String getClientId() {
        return clientId;
    }

    @Nonnull
    public String getClientVersion() {
        return clientVersion;
    }

    @Nullable
    public String getOsName() {
        return osName;
    }

    @Nullable
    public String getOsVersion() {
        return osVersion;
    }

    public boolean isWebLike() {
        return webLike;
    }

    @Nullable
    public String getUserAgent() {
        return userAgent;
    }
}
