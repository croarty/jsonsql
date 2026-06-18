package com.jsonsql.query;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CteNamesTest {

    @Test
    void resolveKeyIgnoresCase() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("expensive", "body");
        assertEquals("expensive", CteNames.resolveKey(map, "Expensive"));
        assertEquals("expensive", CteNames.resolveKey(map, "EXPENSIVE"));
        assertNull(CteNames.resolveKey(map, "missing"));
    }

    @Test
    void containsIgnoresCase() {
        Set<String> names = new LinkedHashSet<>();
        names.add("expensive_products");
        assertTrue(CteNames.contains(names, "Expensive_Products"));
        assertFalse(CteNames.contains(names, "cheap"));
    }
}
