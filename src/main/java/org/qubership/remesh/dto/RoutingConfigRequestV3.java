package org.qubership.remesh.dto;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RoutingConfigRequestV3 {
    private String namespace;
    private List<String> gateways;
    private Integer listenerPort;
    private Boolean tlsSupported;
    private List<VirtualService> virtualServices;
    private Boolean overridden;
}
