package com.jsonsql.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaterializedViewStoreTest {

    @TempDir
    File tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void saveLoadRoundTrip() throws Exception {
        MaterializedViewStore store = new MaterializedViewStore(tempDir);
        JsonNode row = objectMapper.readTree("{\"id\":1,\"name\":\"Widget\"}");
        store.save("expensive", List.of(row));

        List<JsonNode> loaded = store.load("expensive");
        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        assertEquals("Widget", loaded.get(0).get("name").asText());
        assertTrue(store.storeFile("expensive").exists());
    }

    @Test
    void deleteRemovesFile() throws Exception {
        MaterializedViewStore store = new MaterializedViewStore(tempDir);
        store.save("v", List.of());
        assertTrue(store.delete("v"));
        assertNull(store.load("v"));
        assertFalse(store.delete("v"));
    }

    @Test
    void sanitizesUnsafeNames() throws Exception {
        MaterializedViewStore store = new MaterializedViewStore(tempDir);
        store.save("my/view", List.of());
        assertTrue(new File(store.getViewsDirectory(), "my_view.json").exists());
    }
}
