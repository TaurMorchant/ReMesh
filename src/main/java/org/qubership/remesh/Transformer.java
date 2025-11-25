package org.qubership.remesh;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Slf4j
public class Transformer {
    public void transform(Path dir) throws IOException {
        log.info("Transforming in dir {}", dir);
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isYaml)
                    .forEach(p -> log.info("Found file: {}", p.toAbsolutePath()));
        }
    }

    private boolean isYaml(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
