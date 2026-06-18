package com.jsonsql.view;

import com.jsonsql.config.MappingManager;
import com.jsonsql.query.TableFileResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class SourceFingerprintTest {

    @TempDir
    File tempDir;

    private File dataDir;
    private MappingManager mappingManager;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = new File(tempDir, "data");
        assertTrue(dataDir.mkdirs());
        Files.writeString(dataDir.toPath().resolve("products.json"), """
            {"products":[
              {"id":1,"price":50},
              {"id":2,"price":250}
            ]}""");

        File mappingFile = new File(tempDir, "mappings.json");
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "products.json:$.products[*]");
    }

    private MaterializedViewDefinition viewWithFingerprint(File buildDataDir) throws Exception {
        TableFileResolver resolver = new TableFileResolver(mappingManager, buildDataDir);
        String fp = SourceFingerprint.forCteSql(
            "SELECT id, price FROM products WHERE price >= 200", mappingManager, resolver);

        MaterializedViewDefinition def = new MaterializedViewDefinition();
        def.setName("expensive");
        def.setCteSql("SELECT id, price FROM products WHERE price >= 200");
        def.setSourceFingerprint(fp);
        def.setDataDirectory(TableFileResolver.canonicalPath(buildDataDir));
        return def;
    }

    @Test
    void isFreshUsesStoredDataDirectory() throws Exception {
        MaterializedViewDefinition def = viewWithFingerprint(dataDir);
        File parentDir = tempDir;

        assertTrue(SourceFingerprint.isFresh(def, mappingManager, parentDir));
    }

    @Test
    void isFreshDetectsRealSourceChange() throws Exception {
        MaterializedViewDefinition def = viewWithFingerprint(dataDir);

        Files.writeString(dataDir.toPath().resolve("products.json"), """
            {"products":[{"id":9,"price":999}]}""");

        assertFalse(SourceFingerprint.isFresh(def, mappingManager, dataDir));
    }

    @Test
    void legacyViewWithoutDataDirFallsBackToCli() throws Exception {
        TableFileResolver resolver = new TableFileResolver(mappingManager, dataDir);
        String fp = SourceFingerprint.forCteSql(
            "SELECT id FROM products", mappingManager, resolver);

        MaterializedViewDefinition def = new MaterializedViewDefinition();
        def.setName("legacy");
        def.setCteSql("SELECT id FROM products");
        def.setSourceFingerprint(fp);
        // no dataDirectory field

        assertTrue(SourceFingerprint.isFresh(def, mappingManager, dataDir));
        assertFalse(SourceFingerprint.isFresh(def, mappingManager, tempDir));
    }

    @Test
    void fingerprintUsesRelativePaths() throws Exception {
        TableFileResolver resolver = new TableFileResolver(mappingManager, dataDir);
        String fp = SourceFingerprint.forCteSql("SELECT id FROM products", mappingManager, resolver);

        assertTrue(fp.contains("products.json@"));
        assertFalse(fp.contains(dataDir.getAbsolutePath()));
    }

    @Test
    void resolveFreshnessDirectoryPrefersStoredPath() throws Exception {
        MaterializedViewDefinition def = new MaterializedViewDefinition();
        def.setDataDirectory(TableFileResolver.canonicalPath(dataDir));

        assertEquals(TableFileResolver.canonicalPath(dataDir),
            TableFileResolver.canonicalPath(SourceFingerprint.resolveFreshnessDirectory(def, tempDir)));
    }
}
