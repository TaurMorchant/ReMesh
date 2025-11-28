package org.qubership.remesh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.remesh.dto.gatewayapi.Resource;
import org.qubership.remesh.handler.MeshResourceRouter;
import org.qubership.remesh.serialization.YamlPreprocessor;
import org.qubership.remesh.util.ObjectMapperProvider;
import org.qubership.remesh.validation.ResourceValidator;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
public class TransformerService {
    public static final String FRAGMENT_DELIMITER = "(?m)^---\\s*$";
    private final YamlPreprocessor yamlPreprocessor;
    private final MeshResourceRouter meshResourceRouter;
    private final ResourceValidator resourceValidator;
    private final ObjectMapper mapper;

    public TransformerService() {
        this(new YamlPreprocessor(ObjectMapperProvider.getMapper()),
                new MeshResourceRouter(),
                new ResourceValidator(),
                ObjectMapperProvider.getMapper()
        );
    }

    public TransformerService(YamlPreprocessor yamlPreprocessor,
                              MeshResourceRouter meshResourceRouter,
                              ResourceValidator resourceValidator,
                              ObjectMapper mapper) {
        this.yamlPreprocessor = yamlPreprocessor;
        this.meshResourceRouter = meshResourceRouter;
        this.resourceValidator = resourceValidator;
        this.mapper = mapper;
    }

    public void transform(Path dir) throws IOException {
        log.info("Start transforming in dir '{}'", dir);
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isYaml)
                    .forEach(this::processFile);
        }
    }

    boolean isYaml(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    void processFile(Path file) {
        log.info("=== Processing file '{}' ===", file);
        Path outputFile = file.resolveSibling(file.getFileName().toString() + "_new");

        try (Writer writer = Files.newBufferedWriter(outputFile)) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String[] documents = content.split(FRAGMENT_DELIMITER);

            for (String rawDoc : documents) {
                if (rawDoc == null || rawDoc.isBlank()) {
                    continue;
                }

                JsonNode node = yamlPreprocessor.readAsJsonNode(rawDoc);
                if (node == null) {
                    continue;
                }

                List<Resource> resources = meshResourceRouter.route(node);
                if (resources == null || resources.isEmpty()) {
                    continue;
                }

                for (Resource resource : resources) {
                    resourceValidator.validateResource(resource);
                    writer.write("---\n");
                    writer.write(mapper.writeValueAsString(resource));
                }
            }

        } catch (IOException e) {
            log.error("Failed to process file '{}'", file, e);
            return;
        }

        log.info("=== Output file is '{}' ===", outputFile);
    }
}
