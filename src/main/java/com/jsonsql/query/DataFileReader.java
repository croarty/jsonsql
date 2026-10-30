package com.jsonsql.query;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the rows of a data file. Accepts UTF-8 with or without a byte order mark, and UTF-16
 * with one. Problems are reported with the file's path, so a table made of many files
 * shows which one is at fault.
 */
public final class DataFileReader {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Configuration jsonPathConfig = JsonPathConfig.create();

    /**
     * Apply a JSONPath to a file and return the objects it selects.
     */
    public List<JsonNode> readRows(File file, String jsonPath) throws IOException {
        JsonNode result = objectMapper.valueToTree(evaluate(file, readText(file), jsonPath));

        List<JsonNode> rows = new ArrayList<>();
        if (result.isArray()) {
            result.forEach(node -> {
                if (node.isObject()) {
                    rows.add(node);
                }
            });
        } else if (result.isObject()) {
            rows.add(result);
        }
        return rows;
    }

    private Object evaluate(File file, String content, String jsonPath) throws IOException {
        try {
            return JsonPath.using(jsonPathConfig).parse(content).read(jsonPath);
        } catch (InvalidJsonException e) {
            throw new IOException(describe(file, e), e);
        } catch (IllegalArgumentException e) {
            throw new IOException("File contains no JSON: " + file.getPath(), e);
        }
    }

    private static String describe(File file, InvalidJsonException e) {
        if (e.getCause() instanceof JsonProcessingException cause) {
            JsonLocation location = cause.getLocation();
            String position = location == null ? ""
                : " at line " + location.getLineNr() + ", column " + location.getColumnNr();
            return "Invalid JSON in " + file.getPath() + position + ": " + firstLine(cause.getOriginalMessage());
        }
        return "Invalid JSON in " + file.getPath();
    }

    private static String firstLine(String message) {
        return message.lines().findFirst().orElse("");
    }

    private static String readText(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
                return decode(bytes, 3, StandardCharsets.UTF_8);
            }
            if (startsWith(bytes, 0xFE, 0xFF) || startsWith(bytes, 0xFF, 0xFE)) {
                return decode(bytes, 0, StandardCharsets.UTF_16);
            }
            return decode(bytes, 0, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            throw new IOException("File is not valid UTF-8: " + file.getPath() + ". Save it as UTF-8 and try again.", e);
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String decode(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
            .toString();
    }
}
