package org.qubership.remesh.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RouteDestination {
    private String cluster;
    private Boolean tlsSupported;
    private String endpoint;
    private String tlsEndpoint;
    private Integer httpVersion;
    private String tlsConfigName;
    private CircuitBreaker circuitBreaker;
    private TcpKeepalive tcpKeepalive;
}
