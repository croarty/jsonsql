package com.jsonsql;

/**
 * Single source of truth for whether DEBUG diagnostics (stack traces, execution traces)
 * are enabled via the {@code DEBUG} environment variable.
 */
public final class DebugMode {

    private DebugMode() {
    }

    public static boolean isEnabled() {
        return isTruthy(System.getenv("DEBUG"));
    }

    /**
     * Unset, blank, "0", "false", "no", and "off" (case-insensitive) are disabled;
     * any other value is enabled.
     */
    static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase();
        return !(v.isEmpty() || v.equals("0") || v.equals("false") || v.equals("no") || v.equals("off"));
    }
}
