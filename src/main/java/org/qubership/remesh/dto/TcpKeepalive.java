package org.qubership.remesh.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TcpKeepalive {
    private Integer probes;
    private Integer time;
    private Integer interval;
}
