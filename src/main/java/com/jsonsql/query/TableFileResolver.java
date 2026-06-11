package com.jsonsql.query;

import com.jsonsql.config.MappingManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves the JSON file(s) backing a mapped table. Handles single files,
 * relative/absolute paths, and directories (recursively).
 *
 * <p>Shared by the query engine and the indexing subsystem so that build-time and
 * query-time always agree on "the table's files". Metadata files/directories
 * (caches, indexes, and {@code .jsonsql-*} config) are excluded from the recursive
 * walk so they are never treated as source data.
 */
public class TableFileResolver {
    private static final String CACHE_DIR = ".jsonsql-cache";
    private static final String INDEX_DIR = ".jsonsql-index";

    private final MappingManager mappingManager;
    private final File dataDirectory;

    public TableFileResolver(MappingManager mappingManager, File dataDirectory) {
        this.mappingManager = mappingManager;
        this.dataDirectory = dataDirectory;
    }

    /**
     * The file or directory a table maps to (before any directory walk). For tables that
     * fall back to {@code <name>.json}, returns the resolved JSON file.
     */
    public File resolveRoot(String tableName) {
        String fileName = mappingManager.getFileName(tableName);
        if (fileName != null) {
            File fileOrDir = new File(dataDirectory, fileName);
            if (!fileOrDir.isAbsolute() && new File(fileName).isAbsolute()) {
                fileOrDir = new File(fileName);
            }
            return fileOrDir;
        }
        return findJsonFile(tableName);
    }

    /**
     * The base directory that file paths are made relative to: the mapped directory
     * itself for directory-backed tables, otherwise the file's parent directory.
     */
    public File resolveBase(String tableName) {
        File root = resolveRoot(tableName);
        if (root.isDirectory()) {
            return root;
        }
        File parent = root.getParentFile();
        return parent != null ? parent : dataDirectory;
    }

    /**
     * Resolve all JSON files backing a table, recursively for directory mappings.
     */
    public List<File> resolveFiles(String tableName) throws IOException {
        File root = resolveRoot(tableName);
        List<File> jsonFiles = new ArrayList<>();

        if (root.isDirectory()) {
            try (Stream<Path> stream = Files.walk(root.toPath())) {
                List<File> found = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .filter(p -> !isMetadataPath(p))
                    .sorted()
                    .map(Path::toFile)
                    .collect(Collectors.toList());
                if (found.isEmpty()) {
                    throw new IOException("No JSON files found in directory tree: " + root.getAbsolutePath());
                }
                jsonFiles.addAll(found);
            }
        } else {
            jsonFiles.add(root);
        }
        return jsonFiles;
    }

    /**
     * Compute a portable, '/'-separated path for {@code file} relative to {@code base}.
     * Falls back to the file name if the file is not under base.
     */
    public static String relativePath(File base, File file) {
        try {
            Path rel = base.toPath().toAbsolutePath().normalize()
                .relativize(file.toPath().toAbsolutePath().normalize());
            return rel.toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            return file.getName();
        }
    }

    public static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsoluteFile().toPath().normalize().toString();
        }
    }

    private boolean isMetadataPath(Path path) {
        for (Path part : path) {
            String name = part.toString();
            if (CACHE_DIR.equals(name) || INDEX_DIR.equals(name)) {
                return true;
            }
        }
        String fileName = path.getFileName().toString();
        return fileName.startsWith(".jsonsql");
    }

    private File findJsonFile(String tableName) {
        File exactMatch = new File(dataDirectory, tableName + ".json");
        if (exactMatch.exists()) {
            return exactMatch;
        }
        File[] files = dataDirectory.listFiles((dir, name) ->
            name.toLowerCase().equals(tableName.toLowerCase() + ".json"));
        if (files != null && files.length > 0) {
            return files[0];
        }
        return exactMatch;
    }
}
