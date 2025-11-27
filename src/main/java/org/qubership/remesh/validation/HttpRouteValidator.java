package org.qubership.remesh.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.*;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;

@Slf4j
public final class HttpRouteValidator {

    private static final String CRD_RESOURCE =
            "schemas/gateway.networking.k8s.io_httproute.yaml";

    private static final ObjectMapper YAML_MAPPER =
            new ObjectMapper(new YAMLFactory());

    private static final JsonSchema HTTP_ROUTE_SCHEMA = loadSchema();

    private HttpRouteValidator() {
    }

    public static void validate(JsonNode httpRouteJson) {
        try {
            Set<ValidationMessage> errors =
                    HTTP_ROUTE_SCHEMA.validate(httpRouteJson);

            if (!errors.isEmpty()) {
                StringBuilder sb =
                        new StringBuilder("HTTPRoute validation failed:\n");
                for (ValidationMessage e : errors) {
                    sb.append(" - ")
                            .append(e.getPath())
                            .append(": ")
                            .append(e.getMessage())
                            .append('\n');
                }
                log.error(sb.toString());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to validate HTTPRoute YAML", e);
        }
    }

    private static JsonSchema loadSchema() {
        try (InputStream is =
                     HttpRouteValidator.class
                             .getClassLoader()
                             .getResourceAsStream(CRD_RESOURCE)) {

            if (is == null) {
                throw new IllegalStateException(
                        "CRD schema resource not found: " + CRD_RESOURCE);
            }

            JsonNode crd = YAML_MAPPER.readTree(is);

            JsonNode schemaNode = null;
            for (JsonNode v : crd.at("/spec/versions")) {
                if ("v1".equals(v.path("name").asText())) {
                    schemaNode = v.at("/schema/openAPIV3Schema");
                    break;
                }
            }

            if (schemaNode == null) {
                throw new IllegalStateException(
                        "v1 schema not found in CRD");
            }

            // Базовая фабрика и базовый мета-схема для draft 2019-09
            JsonSchemaFactory baseFactory =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V201909);

// Берём стандартный metaschema для 2019-09 как основу
            JsonMetaSchema baseMetaSchema = JsonMetaSchema.getV201909();

// Создаём новый metaschema на основе стандартного и добавляем туда
// «невалидационные» ключи Kubernetes
            JsonMetaSchema kubeMetaSchema = JsonMetaSchema
                    .builder("https://json-schema.org/draft/2019-09/schema", baseMetaSchema)
                    .addKeywords(Arrays.asList(
                            new NonValidationKeyword("x-kubernetes-validations"),
                            new NonValidationKeyword("x-kubernetes-list-type"),
                            new NonValidationKeyword("x-kubernetes-map-type"),
                            new NonValidationKeyword("x-kubernetes-preserve-unknown-fields")
                    ))
                    .build();

// Строим фабрику, которая знает про наш расширенный metaschema
            JsonSchemaFactory factory = JsonSchemaFactory
                    .builder(baseFactory)
                    .addMetaSchema(kubeMetaSchema)
                    .build();

            return factory.getSchema(schemaNode);

        } catch (Exception e) {
            throw new ExceptionInInitializerError(
                    "Failed to load HTTPRoute CRD schema: " + e.getMessage());
        }
    }
}
