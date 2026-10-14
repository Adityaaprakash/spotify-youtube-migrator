package com.spotifyyoutube.migrator.matching.benchmark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Loader and serializer for benchmark dataset JSON documents.
 * <p>
 * Provides offline, deterministic loading from classpath resources, file paths,
 * or input streams, integrating automatic validation via {@link BenchmarkDatasetValidator}.
 */
public class BenchmarkDatasetLoader {

    public static final String DEFAULT_BENCHMARK_RESOURCE_PATH = "/benchmark/ground_truth_benchmark.json";

    private final ObjectMapper objectMapper;
    private final BenchmarkDatasetValidator validator;

    /**
     * Constructs a loader with default configured Jackson ObjectMapper and validator.
     */
    public BenchmarkDatasetLoader() {
        this(createDefaultObjectMapper(), new BenchmarkDatasetValidator());
    }

    /**
     * Constructs a loader with custom ObjectMapper and validator.
     *
     * @param objectMapper custom Jackson mapper
     * @param validator dataset validator
     */
    public BenchmarkDatasetLoader(ObjectMapper objectMapper, BenchmarkDatasetValidator validator) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    /**
     * Loads and validates the default benchmark dataset from the classpath.
     *
     * @return validated BenchmarkDataset
     * @throws BenchmarkValidationException if the dataset fails validation
     * @throws IllegalStateException if the classpath resource cannot be found or read
     */
    public BenchmarkDataset loadDefault() {
        return loadFromClasspath(DEFAULT_BENCHMARK_RESOURCE_PATH);
    }

    /**
     * Loads and validates a benchmark dataset from a specified classpath resource path.
     *
     * @param resourcePath classpath resource path
     * @return validated BenchmarkDataset
     */
    public BenchmarkDataset loadFromClasspath(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath must not be null");
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Benchmark dataset resource not found on classpath: " + resourcePath);
            }
            return loadFromStream(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read benchmark resource: " + resourcePath, e);
        }
    }

    /**
     * Loads and validates a benchmark dataset from an input stream.
     *
     * @param inputStream source input stream
     * @return validated BenchmarkDataset
     */
    public BenchmarkDataset loadFromStream(InputStream inputStream) {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        try {
            BenchmarkDataset dataset = objectMapper.readValue(inputStream, BenchmarkDataset.class);
            validator.validateAndThrow(dataset);
            return dataset;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse benchmark dataset from input stream", e);
        }
    }

    /**
     * Loads and validates a benchmark dataset from a file.
     *
     * @param file source file
     * @return validated BenchmarkDataset
     */
    public BenchmarkDataset loadFromFile(File file) {
        Objects.requireNonNull(file, "file must not be null");
        try {
            BenchmarkDataset dataset = objectMapper.readValue(file, BenchmarkDataset.class);
            validator.validateAndThrow(dataset);
            return dataset;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse benchmark dataset from file: " + file.getPath(), e);
        }
    }

    /**
     * Loads and validates a benchmark dataset from a JSON string.
     *
     * @param json JSON content string
     * @return validated BenchmarkDataset
     */
    public BenchmarkDataset loadFromJson(String json) {
        Objects.requireNonNull(json, "json must not be null");
        try {
            BenchmarkDataset dataset = objectMapper.readValue(json, BenchmarkDataset.class);
            validator.validateAndThrow(dataset);
            return dataset;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse benchmark dataset from JSON string", e);
        }
    }

    /**
     * Serializes a benchmark dataset into a pretty-printed, deterministic JSON string.
     *
     * @param dataset dataset to serialize
     * @return pretty-printed JSON string
     */
    public String toJson(BenchmarkDataset dataset) {
        Objects.requireNonNull(dataset, "dataset must not be null");
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dataset);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize benchmark dataset to JSON", e);
        }
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.registerModule(new Jdk8Module());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        return mapper;
    }
}
