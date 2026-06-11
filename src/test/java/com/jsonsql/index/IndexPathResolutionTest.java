package com.jsonsql.index;

import com.jsonsql.config.MappingManager;
import com.jsonsql.query.ParsedQuery;
import com.jsonsql.query.QueryParser;
import com.jsonsql.query.TableFileResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Path/file-set resolution behavior that index correctness depends on: metadata
 * exclusion from the recursive walk, and relocation matching by relative path.
 */
class IndexPathResolutionTest {

    @TempDir
    File tempDir;

    private MappingManager mappingManager;

    @BeforeEach
    void setUp() {
        mappingManager = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
    }

    private void writeFile(String relPath, String content) throws IOException {
        File f = new File(tempDir, relPath);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    @Test
    void recursiveWalkExcludesMetadataFilesAndDirs() throws IOException {
        writeFile("store/data1.json", "{\"items\":[{\"id\":1}]}");
        writeFile("store/nested/data2.json", "{\"items\":[{\"id\":2}]}");
        // Metadata that must NOT be treated as source data:
        writeFile("store/.jsonsql-mappings.json", "{}");
        writeFile("store/.jsonsql-cache/abc.json", "[]");
        writeFile("store/.jsonsql-index/def.json", "{}");

        mappingManager.addMapping("store", "store:$.items[*]");
        TableFileResolver resolver = new TableFileResolver(mappingManager, tempDir);
        List<File> files = resolver.resolveFiles("store");

        assertEquals(2, files.size(), "only real data files should resolve, got: " + files);
        for (File f : files) {
            String p = f.getPath().replace('\\', '/');
            assertFalse(p.contains(".jsonsql"), "metadata path leaked into data files: " + p);
        }
    }

    @Test
    void deepNestingResolvesAndPrunesByRelativePath() throws Exception {
        writeFile("root/a/b/c/north.json",
            "{\"orders\":[{\"id\":1,\"region\":\"North\"}]}");
        writeFile("root/a/b/c/south.json",
            "{\"orders\":[{\"id\":2,\"region\":\"South\"}]}");
        mappingManager.addMapping("orders", "root:$.orders[*]");

        IndexStore store = new IndexStore(tempDir);
        new IndexBuilder(mappingManager, tempDir, store).build("orders", "region");
        IndexManager indexManager = new IndexManager(new File(tempDir, ".jsonsql-indexes.json"));
        indexManager.addIndex("orders", "region");

        TableFileResolver resolver = new TableFileResolver(mappingManager, tempDir);
        IndexPlanner planner = new IndexPlanner(indexManager, store, mappingManager, resolver);
        ParsedQuery query = new QueryParser().parse("SELECT * FROM orders WHERE region = 'North'");

        List<File> kept = planner.prune(query.getFromTable(), query, resolver.resolveFiles("orders"));
        assertEquals(1, kept.size());
        assertTrue(kept.get(0).getPath().replace('\\', '/').endsWith("north.json"));
    }

    @Test
    void relocatedTreeStillMatchesByRelativePath() throws Exception {
        writeFile("siteA/data/2023/p.json",
            "{\"products\":[{\"id\":1,\"category\":\"Tools\"}]}");
        writeFile("siteA/data/2024/p.json",
            "{\"products\":[{\"id\":2,\"category\":\"Office\"}]}");
        mappingManager.addMapping("products", "siteA/data:$.products[*]");

        IndexStore store = new IndexStore(tempDir);
        new IndexBuilder(mappingManager, tempDir, store).build("products", "category");
        IndexManager indexManager = new IndexManager(new File(tempDir, ".jsonsql-indexes.json"));
        indexManager.addIndex("products", "category");

        // Relocate the tree (preserving timestamps/size) and repoint the mapping.
        copyTreePreservingAttributes(new File(tempDir, "siteA/data"), new File(tempDir, "siteB/data"));
        mappingManager.addMapping("products", "siteB/data:$.products[*]");

        TableFileResolver resolver = new TableFileResolver(mappingManager, tempDir);
        IndexPlanner planner = new IndexPlanner(indexManager, store, mappingManager, resolver);
        ParsedQuery query = new QueryParser().parse("SELECT * FROM products WHERE category = 'Office'");

        List<File> kept = planner.prune(query.getFromTable(), query, resolver.resolveFiles("products"));
        assertEquals(1, kept.size(), "relocated tree should still prune via relPath, got: " + kept);
        assertTrue(kept.get(0).getPath().replace('\\', '/').contains("2024"));
    }

    private void copyTreePreservingAttributes(File srcDir, File dstDir) throws IOException {
        Path src = srcDir.toPath();
        Path dst = dstDir.toPath();
        try (Stream<Path> stream = Files.walk(src)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
