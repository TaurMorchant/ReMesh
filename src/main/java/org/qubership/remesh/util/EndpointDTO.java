package org.qubership.remesh.util;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EndpointDTO {
    private final String scheme;
    private final String host;
    private final String port;
}
