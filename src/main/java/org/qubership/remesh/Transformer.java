package org.qubership.remesh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.qubership.remesh.handler.RouteConfigurationHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Slf4j
public class Transformer {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public void transform(Path dir) throws IOException {
        log.info("Transforming in dir {}", dir);
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isYaml)
                    .forEach(this::processFile2);
        }
    }

    private boolean isYaml(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private void processFile2(Path file) {
        String outputFile = file.getFileName().toString() + "_new";
        log.info("output file {}", outputFile);
        try (InputStream is = Files.newInputStream(file)) {
            MappingIterator<JsonNode> it = YAML.readerFor(JsonNode.class).readValues(is);

            while (it.hasNext()) {
                JsonNode node = it.next();
                JsonNode kindNode = node.get("kind");
                if (kindNode == null) {
                    continue;
                }

                if ("RouteConfiguration".equals(kindNode.asText())) {
                    new RouteConfigurationHandler().handle(node, Path.of(outputFile));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
