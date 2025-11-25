package org.qubership.remesh.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class HeaderMatcher {
    private String name;
    private String value;
    private Boolean regex;
    private Boolean invertMatch;
}
