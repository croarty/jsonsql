package com.jsonsql.query;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

/**
 * Builds the JsonPath configuration used to read data files.
 *
 * Jackson parses the files rather than JsonPath's default json-smart parser,
 * because json-smart 2.6.0 accepts truncated JSON and invents the missing values.
 * The read features keep the common non-standard JSON that files already use working.
 */
public final class JsonPathConfig {

    private JsonPathConfig() {
    }

    public static Configuration create() {
        ObjectMapper mapper = JsonMapper.builder()
            .enable(
                JsonReadFeature.ALLOW_SINGLE_QUOTES,
                JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES,
                JsonReadFeature.ALLOW_TRAILING_COMMA,
                JsonReadFeature.ALLOW_JAVA_COMMENTS,
                JsonReadFeature.ALLOW_YAML_COMMENTS,
                JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS,
                JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS,
                JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS,
                JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

        return Configuration.builder()
            .jsonProvider(new JacksonJsonProvider(mapper))
            .mappingProvider(new JacksonMappingProvider(mapper))
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS)
            .build();
    }
}
