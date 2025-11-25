package org.qubership.remesh.dto;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Rule {
    private RouteMatch match;
    private String prefixRewrite;
    private String hostRewrite;
    private List<HeaderDefinition> addHeaders;
    private List<String> removeHeaders;
    private Boolean allowed;
    private Long timeout;
    private Long idleTimeout;
    private StatefulSession statefulSession;
    private String rateLimit;
    private Boolean deny;
    private String luaFilter;
}
