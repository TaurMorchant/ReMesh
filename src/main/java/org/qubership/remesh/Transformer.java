package org.qubership.remesh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.remesh.handler.CrHandler;
import org.qubership.remesh.handler.CrHandlerRegistry;
import org.qubership.remesh.dto.gatewayapi.Resource;
import org.qubership.remesh.util.ObjectMapperProvider;
import org.qubership.remesh.validation.JsonSchemaValidator;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import java.util.regex.Pattern;

@Slf4j
public class Transformer {
    private static final Pattern API_VERSION_SUFFIX = Pattern.compile("/v\\w+$");
    private static final ObjectMapper MAPPER = ObjectMapperProvider.getMapper();

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

        try (Writer writer = Files.newBufferedWriter(outputFile)) {

            String content = Files.readString(file, StandardCharsets.UTF_8);

            String[] documents = content.split("(?m)^---\\s*$");

            for (String rawDoc : documents) {
                if (rawDoc == null || rawDoc.isBlank()) {
                    continue;
                }

                JsonNode node = readAsJsonNode(rawDoc);
                if (node == null) continue;

                JsonNode apiVersion = node.get("apiVersion");
                JsonNode kind = node.get("kind");
                JsonNode subKind = node.get("subKind");

                if (apiVersion == null || kind == null || subKind == null) {
                    continue;
                }

                if (!isMeshResource(apiVersion, kind)) {
                    continue;
                }

                CrHandler handler = CrHandlerRegistry.get(subKind.asText());
                if (handler == null) {
                    log.warn("    Handler not found for kind {}", subKind.asText());
                    continue;
                }

                List<Resource> resources = handler.handle(node);
                if (resources == null || resources.isEmpty()) {
                    continue;
                }

                for (Resource resource : resources) {
                    validateResource(resource);
                    writer.write("---\n");
                    writer.write(MAPPER.writeValueAsString(resource));
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("=== Output file is '{}' ===", outputFile);
    }

    private JsonNode readAsJsonNode(String rawDoc) {
        String preprocessed = preprocessYaml(rawDoc);

        JsonNode node;
        try {
            node = MAPPER.readTree(preprocessed);
        }
        catch (Exception e) {
            log.warn("    Failed to parse document, skipping. Cause: {}", e.getMessage());
            return null;
        }
        return node;
    }

    private static String preprocessYaml(String yaml) {
        yaml = replaceStandaloneTemplates(yaml);  // level: mapping element
        yaml = quoteInlineTemplates(yaml);         // level: scalar value
        return yaml;
    }

    private static String replaceStandaloneTemplates(String yaml) {
        // Любая строка, которая целиком состоит из {{ ... }}, с сохранением отступа
        return yaml.replaceAll(
                "(?m)^(\\s*)\\{\\{[^\\n]*}}\\s*$",
                "$1__helm_standalone_placeholder__: '__helm__'"
        );
    }

    private static String quoteInlineTemplates(String yaml) {
        Pattern p = Pattern.compile(
                "(?m)^([ \\t]*[^:#\\n]+:)[ \\t]*" +          // key:
                "(?![ \\t]*['\"])"+                          // value не начинается с кавычки (с учётом пробелов)
                "([^\\n]*\\{\\{[^\\n]+}}[^\\n]*)$"           // value содержит хотя бы один {{ ... }}
        );

        Matcher m = p.matcher(yaml);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String keyPart = m.group(1);
            String valuePart = m.group(2).trim();

            // Не трогаем значения, которые выглядят как flow-списки: ["{{ ... }}"]
            if (valuePart.indexOf('[') >= 0 || valuePart.indexOf(']') >= 0) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }

            // YAML single-quoted scalar: '...' c экранированием ' -> ''
            String quotedValue = "'" + valuePart.replace("'", "''") + "'";

            m.appendReplacement(
                    sb,
                    Matcher.quoteReplacement(keyPart + " " + quotedValue)
            );
        }

        m.appendTail(sb);
        return sb.toString();
    }

    private boolean isMeshResource(JsonNode apiVersion, JsonNode kind) {
        return "core.netcracker.com/v1".equals(apiVersion.asText()) && "Mesh".equals(kind.asText());
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
