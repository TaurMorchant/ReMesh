package org.qubership.remesh.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Cookie {
    private String name;
    private String path;
    private String domain;
    private String sameSite;
    private Boolean httpOnly;
    private Boolean secure;
    private String ttl;
}
