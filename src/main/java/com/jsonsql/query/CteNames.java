package com.jsonsql.query;

import java.util.Map;
import java.util.Set;

/**
 * Case-insensitive CTE name resolution (SQL identifiers are typically case-insensitive).
 */
public final class CteNames {

    private CteNames() {
    }

    /**
     * Find the map key matching {@code name}, ignoring case.
     */
    public static String resolveKey(Map<String, ?> map, String name) {
        if (name == null || map == null) {
            return null;
        }
        for (String key : map.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    /**
     * Whether {@code set} contains a name equal to {@code name}, ignoring case.
     */
    public static boolean contains(Set<String> set, String name) {
        if (name == null || set == null) {
            return false;
        }
        for (String candidate : set) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
