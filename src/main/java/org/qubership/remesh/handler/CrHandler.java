package org.qubership.remesh.handler;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;

public interface CrHandler {
    String getKind();
    void handle(JsonNode node, Path outputFil);
}
