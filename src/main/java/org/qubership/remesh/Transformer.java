package org.qubership.remesh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import lombok.extern.slf4j.Slf4j;
import org.qubership.remesh.handler.CrHandler;
import org.qubership.remesh.handler.CrHandlerRegistry;
import org.qubership.remesh.dto.gatewayapi.Resource;
import org.qubership.remesh.util.ObjectMapperProvider;
import org.qubership.remesh.validation.JsonSchemaValidator;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.regex.Pattern;

@Slf4j
public class Transformer {
    private static final Pattern API_VERSION_SUFFIX = Pattern.compile("/v\\w+$");

    public void transform(Path dir) throws IOException {
        log.info("Start transforming in dir '{}'", dir);
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isYaml)
                    .forEach(this::processFile);
        }
    }

    private boolean isYaml(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private void processFile(Path file) {
        log.info("=== Processing file '{}' ===", file);
        Path outputFile = file.resolveSibling(file.getFileName().toString() + "_new");
        try (InputStream is = Files.newInputStream(file);
             Writer writer = Files.newBufferedWriter(outputFile)) {
            MappingIterator<JsonNode> it = ObjectMapperProvider.getMapper().readerFor(JsonNode.class).readValues(is);

            while (it.hasNext()) {
                JsonNode node = it.next();
                JsonNode kindNode = node.get("kind");
                if (kindNode == null) {
                    continue;
                }

                CrHandler handler = CrHandlerRegistry.get(kindNode.asText());
                if (handler == null) {
                    log.warn("    Handler not found for kind {}", kindNode.asText());
                }
                else {
                    List<Resource> resources = handler.handle(node);
                    if (resources != null) {
                        for (Resource resource : resources) {
                            validateResource(resource);
                            writer.write("---\n");
                            writer.write(ObjectMapperProvider.getMapper().writeValueAsString(resource));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        log.info("=== Output file is '{}' ===", outputFile);
    }

    private void validateResource(Resource resource) {
        JsonNode resourceNode = ObjectMapperProvider.getMapper().valueToTree(resource);
        log.info("    Start validating {}", Optional.ofNullable(resource.getKind()).orElse("resource"));
        String schemaFileName = schemaFileName(resource);
        JsonSchemaValidator.validate(resourceNode, schemaFileName);
    }

    private String schemaFileName(Resource resource) {
        String apiVersion = Optional.ofNullable(resource.getApiVersion()).orElse("");
        String kind = Optional.ofNullable(resource.getKind()).orElse("");

        String baseApiVersion = API_VERSION_SUFFIX.matcher(apiVersion).replaceFirst("");

        return (baseApiVersion + "_" + kind).toLowerCase() + ".yaml";
    }
}
