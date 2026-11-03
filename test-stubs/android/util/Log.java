package android.util;

/** Host-test-only stand-in; production builds use Android's real logger. */
public final class Log {
    private Log() {}
    public static int i(String tag, String message) { return 0; }
}
