package org.qubership.remesh.handler;

public interface CrHandler {
    String getKind();
    void handle(String spec, String metadata);
}
