package com.jsonsql.index;

import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The index builder reads data files itself, so it must apply the same parsing
 * rules as queries or it could index rows that a query would reject.
 */
class IndexBuilderParsingTest {

    @TempDir
    File tempDir;

    private MappingManager mappingManager;
    private IndexBuilder builder;

    @BeforeEach
    void setUp() {
        mappingManager = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        builder = new IndexBuilder(mappingManager, tempDir, new IndexStore(tempDir));
    }

    @Test
    void truncatedFileIsNotIndexed() throws Exception {
        Files.writeString(new File(tempDir, "cut.json").toPath(), "{\"items\": [{\"id\": 1}, {\"id\"");
        mappingManager.addMapping("cut", "cut.json:$.items");

        assertThrows(Exception.class, () -> builder.build("cut", "id"));
    }

    @Test
    void fileWithCommentsIsIndexed() throws Exception {
        Files.writeString(new File(tempDir, "notes.json").toPath(), "// notes\n{\"items\": [{\"id\": 1}]}");
        mappingManager.addMapping("notes", "notes.json:$.items");

        assertNotNull(builder.build("notes", "id"));
    }
}
