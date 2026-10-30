package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataFileReaderTest {

    private static final String JSON = "{\"rows\": [{\"id\": 1, \"n\": \"café\"}]}";

    @TempDir
    File tempDir;

    private final DataFileReader reader = new DataFileReader();

    private File write(String name, byte[] bytes) throws Exception {
        File file = new File(tempDir, name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static byte[] withPrefix(int[] prefix, String text, Charset charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int b : prefix) {
            out.write(b);
        }
        out.writeBytes(text.getBytes(charset));
        return out.toByteArray();
    }

    private void assertReadsTheRow(File file) throws Exception {
        List<JsonNode> rows = reader.readRows(file, "$.rows[*]");
        assertEquals(1, rows.size());
        assertEquals("café", rows.get(0).get("n").asText());
    }

    @Test
    void readsPlainUtf8() throws Exception {
        assertReadsTheRow(write("a.json", JSON.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsUtf8WithAByteOrderMark() throws Exception {
        assertReadsTheRow(write("a.json", withPrefix(new int[]{0xEF, 0xBB, 0xBF}, JSON, StandardCharsets.UTF_8)));
    }

    @Test
    void readsUtf16LittleEndianWithAByteOrderMark() throws Exception {
        assertReadsTheRow(write("a.json", withPrefix(new int[]{0xFF, 0xFE}, JSON, StandardCharsets.UTF_16LE)));
    }

    @Test
    void readsUtf16BigEndianWithAByteOrderMark() throws Exception {
        assertReadsTheRow(write("a.json", withPrefix(new int[]{0xFE, 0xFF}, JSON, StandardCharsets.UTF_16BE)));
    }

    @Test
    void reportsANonUtf8FileByName() throws Exception {
        File file = write("latin.json", JSON.getBytes(StandardCharsets.ISO_8859_1));

        IOException e = assertThrows(IOException.class, () -> reader.readRows(file, "$.rows[*]"));

        assertTrue(e.getMessage().contains("not valid UTF-8"), e.getMessage());
        assertTrue(e.getMessage().contains(file.getPath()), e.getMessage());
    }

    @Test
    void reportsTruncatedJsonWithFileAndPosition() throws Exception {
        File file = write("cut.json", "{\"rows\": [\n{\"id\": 1}".getBytes(StandardCharsets.UTF_8));

        IOException e = assertThrows(IOException.class, () -> reader.readRows(file, "$.rows[*]"));

        assertTrue(e.getMessage().startsWith("Invalid JSON in " + file.getPath() + " at line 2"), e.getMessage());
    }

    @Test
    void errorsDoNotShowInternalClassNames() throws Exception {
        File file = write("cut.json", "{\"rows\": [".getBytes(StandardCharsets.UTF_8));

        IOException e = assertThrows(IOException.class, () -> reader.readRows(file, "$.rows[*]"));

        assertFalse(e.getMessage().contains("com.fasterxml"), e.getMessage());
        assertFalse(e.getMessage().contains("\n"), e.getMessage());
    }

    @Test
    void reportsAnEmptyFile() throws Exception {
        File file = write("empty.json", new byte[0]);

        IOException e = assertThrows(IOException.class, () -> reader.readRows(file, "$.rows[*]"));

        assertTrue(e.getMessage().contains("no JSON"), e.getMessage());
        assertTrue(e.getMessage().contains(file.getPath()), e.getMessage());
    }

    @Test
    void reportsAWhitespaceOnlyFile() throws Exception {
        File file = write("blank.json", "  \n".getBytes(StandardCharsets.UTF_8));

        IOException e = assertThrows(IOException.class, () -> reader.readRows(file, "$.rows[*]"));

        assertTrue(e.getMessage().contains(file.getPath()), e.getMessage());
    }

    @Test
    void aSingleObjectIsOneRow() throws Exception {
        File file = write("a.json", "{\"row\": {\"id\": 1}}".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, reader.readRows(file, "$.row").size());
    }

    @Test
    void valuesThatAreNotObjectsAreSkipped() throws Exception {
        File file = write("a.json", "{\"rows\": [1, {\"id\": 1}, \"x\"]}".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, reader.readRows(file, "$.rows[*]").size());
    }
}
