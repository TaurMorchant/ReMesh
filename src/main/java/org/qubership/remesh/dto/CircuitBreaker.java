package org.qubership.remesh.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CircuitBreaker {
    private Threshold threshold;
}
