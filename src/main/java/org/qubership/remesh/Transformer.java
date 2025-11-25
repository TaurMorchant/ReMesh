package org.qubership.remesh;

import lombok.extern.slf4j.Slf4j;
import org.qubership.remesh.handler.RouteConfigurationHandler;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Slf4j
public class Transformer {
    public void transform(Path dir) throws IOException {
        log.info("Transforming in dir {}", dir);
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
        log.info("Found file: {}", file.toAbsolutePath());
        try (InputStream inputStream = Files.newInputStream(file)) {
            Yaml yaml = new Yaml();
            AtomicInteger fragmentCounter = new AtomicInteger();
            for (Object doc : yaml.loadAll(inputStream)) {
                int current = fragmentCounter.incrementAndGet();
                if (doc instanceof Map<?, ?> fragmentMap) {
                    Object kind = fragmentMap.get("kind");
                    if ("RouteConfiguration".equals(kind)) {
                        Object spec = fragmentMap.get("spec");
                        if (spec == null) {
                            log.warn("RouteConfiguration fragment in {} has no spec section", file.toAbsolutePath());
                            continue;
                        }
                        try {
                            String specDump = yaml.dump(spec);
                            new RouteConfigurationHandler().handle(specDump);
                        } catch (Exception e) {
                            log.error("Failed to parse spec for RouteConfiguration fragment in {}", file.toAbsolutePath(), e);
                        }
                    } else {
                        log.info("Skipping fragment #{} in {} with kind {}", current, file.toAbsolutePath(), kind);
                    }
                } else {
                    log.info("Skipping fragment #{} in {} because content is not a mapping", current, file.toAbsolutePath());
                }
            }
            if (fragmentCounter.get() == 0) {
                log.info("No fragments found in {}", file.toAbsolutePath());
            }
        } catch (MarkedYAMLException e) {
            log.error("Failed to parse YAML file {}", file.toAbsolutePath(), e);
        } catch (IOException e) {
            log.error("Failed to read file {}", file.toAbsolutePath(), e);
        }
    }
}
