package com.example.tab_able.data;

import androidx.annotation.Nullable;

/**
 * In-memory session holder for the signed-in account.
 *
 * The authoritative copy of this data lives in Firestore at users/{uid}.
 * This class is only a fast cache so UI code can read the current identity
 * synchronously; it is always repopulated from Firestore on launch.
 */
public class CurrentUser {

    private static String uid;
    private static String username;
    private static String displayName;
    private static String email;

    private CurrentUser() {
    }

    public static void setSession(@Nullable String uId,
                                  @Nullable String uname,
                                  @Nullable String name) {
        setSession(uId, uname, name, null);
    }

    public static void setSession(@Nullable String uId,
                                  @Nullable String uname,
                                  @Nullable String name,
                                  @Nullable String mail) {
        uid = uId;
        username = uname;
        displayName = name;
        email = mail;
    }

    public static void clear() {
        uid = null;
        username = null;
        displayName = null;
        email = null;
    }

    @Nullable
    public static String getUid() {
        return uid;
    }

    @Nullable
    public static String getUsername() {
        return username;
    }

    /** Never null — falls back through username, then a generic label. */
    public static String getDisplayName() {
        if (displayName != null && !displayName.trim().isEmpty()) return displayName;
        if (username != null && !username.trim().isEmpty()) return username;
        return "User";
    }

    @Nullable
    public static String getEmail() {
        return email;
    }

    public static boolean isSignedIn() {
        return uid != null && !uid.isEmpty();
    }
}
