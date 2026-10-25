package com.jsonsql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DebugModeTest {

    @Test
    void unsetIsDisabled() {
        assertFalse(DebugMode.isTruthy(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "0", "false", "FALSE", "False", "no", "NO", "off", "Off", " 0 ", " false "})
    void falsyValuesAreDisabled(String value) {
        assertFalse(DebugMode.isTruthy(value), "'" + value + "' must disable debug output");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "TRUE", "yes", "on", "verbose", "2", " true "})
    void truthyValuesAreEnabled(String value) {
        assertTrue(DebugMode.isTruthy(value), "'" + value + "' must enable debug output");
    }

    @Test
    void cliDelegatesToDebugMode() {
        assertEquals(DebugMode.isEnabled(), JsonSqlCli.isDebugEnabled());
    }
}
