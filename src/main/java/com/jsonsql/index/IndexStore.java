package com.jsonsql.index;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Reads and writes per-{@code (table, field)} index summaries under a
 * {@code .jsonsql-index} directory inside the data directory.
 *
 * <p>Files use the {@code .idx} extension (not {@code .json}) so the recursive table
 * loader never mistakes an index file for source data.
 */
public class IndexStore {
    private final File indexDirectory;
    private final ObjectMapper objectMapper;

    public IndexStore(File dataDirectory) {
        this.indexDirectory = new File(dataDirectory, ".jsonsql-index");
        this.objectMapper = new ObjectMapper();
        // Tolerate older/newer index files that carry extra fields rather than discarding them.
        this.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        if (!indexDirectory.exists() && !indexDirectory.mkdirs() && !indexDirectory.exists()) {
            throw new IllegalStateException(
                "Failed to create index directory: " + indexDirectory.getAbsolutePath());
        }
    }

    public File getIndexDirectory() {
        return indexDirectory;
    }

    /** Load the summary for a (table, field), or null if it does not exist / is corrupt. */
    public TableIndex load(String table, String field) {
        File file = indexFile(table, field);
        if (!file.exists()) {
            return null;
        }
        try {
            return objectMapper.readValue(file, TableIndex.class);
        } catch (IOException e) {
            // Treat a corrupt index as absent so the engine falls back to a full scan.
            return null;
        }
    }

    public void save(TableIndex index) throws IOException {
        File file = indexFile(index.getTable(), index.getField());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, index);
    }

    /** Delete the summary file for a (table, field). Returns true if a file was removed. */
    public boolean delete(String table, String field) {
        File file = indexFile(table, field);
        return file.exists() && file.delete();
    }

    private File indexFile(String table, String field) {
        String key = table + "|" + field;
        return new File(indexDirectory, hash(key) + ".idx");
    }

    private String hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(key.hashCode());
        }
    }
}
