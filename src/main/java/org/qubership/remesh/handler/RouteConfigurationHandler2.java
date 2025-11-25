package org.qubership.remesh.handler;

import lombok.extern.slf4j.Slf4j;
import org.qubership.remesh.dto.CircuitBreaker;
import org.qubership.remesh.dto.Cookie;
import org.qubership.remesh.dto.HeaderDefinition;
import org.qubership.remesh.dto.HeaderMatcher;
import org.qubership.remesh.dto.RouteConfig;
import org.qubership.remesh.dto.RouteDestination;
import org.qubership.remesh.dto.RouteMatch;
import org.qubership.remesh.dto.RouteV3;
import org.qubership.remesh.dto.RoutingConfigRequestV3;
import org.qubership.remesh.dto.Rule;
import org.qubership.remesh.dto.StatefulSession;
import org.qubership.remesh.dto.TcpKeepalive;
import org.qubership.remesh.dto.VirtualService;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.PropertyUtils;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RouteConfigurationHandler2 converts RouteConfiguration CR into Gateway API HTTPRoutes and
 * Istio manifests (VirtualService, DestinationRule, ServiceEntry) using the documented mapping.
 */
@Slf4j
public class RouteConfigurationHandler2 implements CrHandler {
    private static final String OUTPUT_FILE = "generated-gateway-istio.yaml-new";
    private static final Yaml routingConfigYaml = createRoutingConfigYaml();

    @Override
    public String getKind() {
        return "RouteConfiguration";
    }

    @Override
    public void handle(String specDump, String metadataDump) {
        RoutingConfigRequestV3 routingConfig = routingConfigYaml.load(specDump);
        if (routingConfig == null) {
            log.warn("Failed to parse RouteConfiguration fragment");
            return;
        }

        Map<String, Object> crMetadata = metadataDump != null ? new Yaml().load(metadataDump) : Map.of();
        if (routingConfig.getNamespace() == null && crMetadata.get("namespace") instanceof String namespace) {
            routingConfig.setNamespace(namespace);
        }

        List<Map<String, Object>> manifests = new ArrayList<>();
        List<VirtualService> virtualServices = Optional.ofNullable(routingConfig.getVirtualServices()).orElse(List.of());

        for (VirtualService virtualService : virtualServices) {
            manifests.add(buildHttpRouteResource(virtualService, routingConfig, crMetadata));
            manifests.add(buildVirtualService(virtualService, routingConfig, crMetadata));

            RouteConfig routeConfiguration = virtualService.getRouteConfiguration();
            List<RouteV3> routes = routeConfiguration != null
                    ? Optional.ofNullable(routeConfiguration.getRoutes()).orElse(List.of())
                    : List.of();
            AtomicInteger destinationCounter = new AtomicInteger();

            for (RouteV3 route : routes) {
                int index = destinationCounter.incrementAndGet();
                String suffix = "-" + index;
                manifests.add(buildDestinationRule(virtualService, routingConfig, route, suffix, crMetadata));
                buildServiceEntry(virtualService, routingConfig, route, suffix, crMetadata).ifPresent(manifests::add);
            }
        }

        writeManifests(manifests);
        log.info("Generated {} Gateway API/Istio manifest(s) from RouteConfiguration", manifests.size());
    }

    private static Yaml createRoutingConfigYaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(RoutingConfigRequestV3.class, loaderOptions);
        PropertyUtils propertyUtils = new PropertyUtils();
        propertyUtils.setSkipMissingProperties(true);
        constructor.setPropertyUtils(propertyUtils);
        return new Yaml(constructor);
    }

    private static Yaml outputYaml() {
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setPrettyFlow(true);
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        dumperOptions.setIndent(2);
        return new Yaml(dumperOptions);
    }

    private Map<String, Object> buildHttpRouteResource(VirtualService virtualService,
                                                       RoutingConfigRequestV3 routingConfig,
                                                       Map<String, Object> crMetadata) {
        Map<String, Object> httpRoute = new LinkedHashMap<>();
        httpRoute.put("apiVersion", "gateway.networking.k8s.io/v1beta1");
        httpRoute.put("kind", "HTTPRoute");

        RouteConfig routeConfiguration = virtualService.getRouteConfiguration();
        Map<String, Object> metadata = buildMetadata(virtualService.getName(), routingConfig.getNamespace(), crMetadata);
        Map<String, Object> annotations = (Map<String, Object>) metadata.computeIfAbsent("annotations", key -> new LinkedHashMap<>());
        if (routeConfiguration != null && routeConfiguration.getVersion() != null) {
            annotations.put("remesh.qubership.org/route-version", routeConfiguration.getVersion());
        }
        if (Boolean.TRUE.equals(routingConfig.getOverridden())) {
            annotations.put("remesh.qubership.org/routing-overridden", "true");
        }
        if (Boolean.TRUE.equals(virtualService.getOverridden())) {
            annotations.put("remesh.qubership.org/virtual-service-overridden", "true");
        }
        httpRoute.put("metadata", metadata);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("hostnames", Optional.ofNullable(virtualService.getHosts()).orElse(List.of()));

        List<String> gateways = Optional.ofNullable(routingConfig.getGateways()).orElse(List.of());
        if (!gateways.isEmpty()) {
            List<Map<String, Object>> parentRefs = new ArrayList<>();
            for (String gateway : gateways) {
                Map<String, Object> parent = new LinkedHashMap<>();
                parent.put("name", gateway);
                if (routingConfig.getNamespace() != null) {
                    parent.put("namespace", routingConfig.getNamespace());
                }
                if (routingConfig.getListenerPort() != null) {
                    parent.put("port", routingConfig.getListenerPort());
                }
                parentRefs.add(parent);
            }
            spec.put("parentRefs", parentRefs);
        }

        List<Map<String, Object>> rules = new ArrayList<>();
        List<RouteV3> routes = routeConfiguration != null
                ? Optional.ofNullable(routeConfiguration.getRoutes()).orElse(List.of())
                : List.of();
        for (RouteV3 route : routes) {
            RouteDestination destination = route.getDestination();
            List<Rule> virtualServiceRules = Optional.ofNullable(route.getRules()).orElse(List.of());
            for (Rule rule : virtualServiceRules) {
                if (Boolean.FALSE.equals(rule.getAllowed())) {
                    continue;
                }
                Map<String, Object> ruleBlock = new LinkedHashMap<>();

                List<Map<String, Object>> matches = buildHttpRouteMatches(rule.getMatch());
                if (!matches.isEmpty()) {
                    ruleBlock.put("matches", matches);
                }

                List<Map<String, Object>> filters = new ArrayList<>();
                Map<String, Object> headerModifier = buildHttpRouteHeaders(virtualService, rule);
                if (!headerModifier.isEmpty()) {
                    filters.add(Map.of(
                            "type", "RequestHeaderModifier",
                            "requestHeaderModifier", headerModifier
                    ));
                }
                Map<String, Object> urlRewrite = buildUrlRewrite(rule);
                if (!urlRewrite.isEmpty()) {
                    filters.add(Map.of(
                            "type", "URLRewrite",
                            "urlRewrite", urlRewrite
                    ));
                }
                Optional.ofNullable(rule.getRateLimit())
                        .or(() -> Optional.ofNullable(virtualService.getRateLimit()))
                        .ifPresent(rateLimit -> filters.add(Map.of(
                                "type", "ExtensionRef",
                                "extensionRef", Map.of(
                                        "group", "gateway.envoyproxy.io",
                                        "kind", "RateLimitPolicy",
                                        "name", rateLimit
                                )
                        )));
                if (!filters.isEmpty()) {
                    ruleBlock.put("filters", filters);
                }

                Map<String, Object> backendRef = buildBackendRef(destination, routingConfig);
                if (!backendRef.isEmpty()) {
                    ruleBlock.put("backendRefs", List.of(backendRef));
                }

                rules.add(ruleBlock);
            }
        }

        spec.put("rules", rules);
        httpRoute.put("spec", spec);
        return httpRoute;
    }

    private List<Map<String, Object>> buildHttpRouteMatches(RouteMatch match) {
        if (match == null) {
            return List.of();
        }
        Map<String, Object> matchBlock = new LinkedHashMap<>();
        if (match.getPrefix() != null) {
            matchBlock.put("path", Map.of(
                    "type", "PathPrefix",
                    "value", match.getPrefix()
            ));
        } else if (match.getPath() != null) {
            matchBlock.put("path", Map.of(
                    "type", "Exact",
                    "value", match.getPath()
            ));
        } else if (match.getRegExp() != null) {
            matchBlock.put("path", Map.of(
                    "type", "RegularExpression",
                    "value", match.getRegExp()
            ));
        }

        List<Map<String, Object>> headers = new ArrayList<>();
        for (HeaderMatcher headerMatcher : Optional.ofNullable(match.getHeaderMatchers()).orElse(List.of())) {
            Map<String, Object> headerMatch = new LinkedHashMap<>();
            headerMatch.put("name", headerMatcher.getName());
            headerMatch.put("value", headerMatcher.getValue());
            if (Boolean.TRUE.equals(headerMatcher.getRegex())) {
                headerMatch.put("type", "RegularExpression");
            } else {
                headerMatch.put("type", "Exact");
            }
            if (Boolean.TRUE.equals(headerMatcher.getInvertMatch())) {
                headerMatch.put("invert", true);
            }
            headers.add(headerMatch);
        }
        if (!headers.isEmpty()) {
            matchBlock.put("headers", headers);
        }

        if (matchBlock.isEmpty()) {
            return List.of();
        }
        return List.of(matchBlock);
    }

    private Map<String, Object> buildHttpRouteHeaders(VirtualService virtualService, Rule rule) {
        Map<String, Object> headers = new LinkedHashMap<>();
        Map<String, String> add = new LinkedHashMap<>();
        for (HeaderDefinition header : Optional.ofNullable(virtualService.getAddHeaders()).orElse(List.of())) {
            add.put(header.getName(), header.getValue());
        }
        for (HeaderDefinition header : Optional.ofNullable(rule.getAddHeaders()).orElse(List.of())) {
            add.put(header.getName(), header.getValue());
        }
        if (!add.isEmpty()) {
            headers.put("add", add);
        }

        List<String> remove = new ArrayList<>();
        remove.addAll(Optional.ofNullable(virtualService.getRemoveHeaders()).orElse(List.of()));
        remove.addAll(Optional.ofNullable(rule.getRemoveHeaders()).orElse(List.of()));
        if (!remove.isEmpty()) {
            headers.put("remove", remove);
        }
        return headers;
    }

    private Map<String, Object> buildUrlRewrite(Rule rule) {
        Map<String, Object> rewrite = new LinkedHashMap<>();
        if (rule.getPrefixRewrite() != null) {
            rewrite.put("path", Map.of("replacePrefixMatch", rule.getPrefixRewrite()));
        }
        if (rule.getHostRewrite() != null) {
            rewrite.put("hostname", rule.getHostRewrite());
        }
        return rewrite;
    }

    private Map<String, Object> buildBackendRef(RouteDestination destination, RoutingConfigRequestV3 routingConfig) {
        Map<String, Object> backend = new LinkedHashMap<>();
        if (destination == null) {
            return backend;
        }
        if (destination.getCluster() != null) {
            backend.put("name", destination.getCluster());
        }
        if (routingConfig.getNamespace() != null) {
            backend.put("namespace", routingConfig.getNamespace());
        }
        Endpoint endpoint = parseEndpoint(destination.getEndpoint());
        String port = endpoint.port();
        if (port == null) {
            port = routingConfig.getListenerPort().toString();//todo vlla точно корректно?
        }
        if (port != null) {
            backend.put("port", port);
        }
        return backend;
    }

    private Map<String, Object> buildVirtualService(VirtualService virtualService,
                                                    RoutingConfigRequestV3 routingConfig,
                                                    Map<String, Object> crMetadata) {
        Map<String, Object> vs = new LinkedHashMap<>();
        vs.put("apiVersion", "networking.istio.io/v1beta1");
        vs.put("kind", "VirtualService");

        Map<String, Object> metadata = buildMetadata(virtualService.getName(), routingConfig.getNamespace(), crMetadata);
        RouteConfig routeConfiguration = virtualService.getRouteConfiguration();
        if (routeConfiguration != null && routeConfiguration.getVersion() != null) {
            Map<String, Object> annotations = (Map<String, Object>) metadata.computeIfAbsent("annotations", key -> new LinkedHashMap<>());
            annotations.put("remesh.qubership.org/route-version", routeConfiguration.getVersion());
        }
        vs.put("metadata", metadata);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("hosts", Optional.ofNullable(virtualService.getHosts()).orElse(List.of()));
        spec.put("gateways", Optional.ofNullable(routingConfig.getGateways()).orElse(List.of()));

        List<Map<String, Object>> httpRoutes = new ArrayList<>();
        List<RouteV3> routes = routeConfiguration != null
                ? Optional.ofNullable(routeConfiguration.getRoutes()).orElse(List.of())
                : List.of();

        for (RouteV3 route : routes) {
            RouteDestination destination = route.getDestination();
            List<Rule> rules = Optional.ofNullable(route.getRules()).orElse(List.of());
            for (Rule rule : rules) {
                if (Boolean.FALSE.equals(rule.getAllowed())) {
                    continue;
                }
                httpRoutes.add(buildIstioHttpRoute(rule, destination, virtualService));
            }
        }

        spec.put("http", httpRoutes);
        vs.put("spec", spec);
        return vs;
    }

    private Map<String, Object> buildIstioHttpRoute(Rule rule, RouteDestination destination, VirtualService virtualService) {
        Map<String, Object> httpRoute = new LinkedHashMap<>();

        RouteMatch match = rule.getMatch();
        if (match != null) {
            Map<String, Object> matchEntry = new LinkedHashMap<>();
            Map<String, Object> uri = new LinkedHashMap<>();
            if (match.getPrefix() != null) {
                uri.put("prefix", match.getPrefix());
            }
            if (match.getRegExp() != null) {
                uri.put("regex", match.getRegExp());
            }
            if (match.getPath() != null) {
                uri.put("exact", match.getPath());
            }
            if (!uri.isEmpty()) {
                matchEntry.put("uri", uri);
            }

            Map<String, Object> headers = new LinkedHashMap<>();
            for (HeaderMatcher headerMatcher : Optional.ofNullable(match.getHeaderMatchers()).orElse(List.of())) {
                Map<String, Object> matcher = new LinkedHashMap<>();
                if (Boolean.TRUE.equals(headerMatcher.getRegex())) {
                    matcher.put("regex", headerMatcher.getValue());
                } else {
                    matcher.put("exact", headerMatcher.getValue());
                }
                if (Boolean.TRUE.equals(headerMatcher.getInvertMatch())) {
                    matcher.put("invertMatch", true);
                }
                headers.put(headerMatcher.getName(), matcher);
            }
            if (!headers.isEmpty()) {
                matchEntry.put("headers", headers);
            }
            if (!matchEntry.isEmpty()) {
                httpRoute.put("match", List.of(matchEntry));
            }
        }

        Map<String, Object> route = new LinkedHashMap<>();
        Map<String, Object> destinationBlock = new LinkedHashMap<>();
        destinationBlock.put("host", destination.getCluster());
        Endpoint endpoint = parseEndpoint(destination.getEndpoint());
        String port = endpoint.port();
        if (port != null) {
            destinationBlock.put("port", Map.of("number", port));
        }
        route.put("destination", destinationBlock);
        httpRoute.put("route", List.of(route));

        Map<String, Object> rewrite = new LinkedHashMap<>();
        if (rule.getPrefixRewrite() != null) {
            rewrite.put("uri", rule.getPrefixRewrite());
        }
        if (rule.getHostRewrite() != null) {
            rewrite.put("authority", rule.getHostRewrite());
        }
        if (!rewrite.isEmpty()) {
            httpRoute.put("rewrite", rewrite);
        }

        Map<String, Object> headers = buildHeaders(virtualService.getAddHeaders(), virtualService.getRemoveHeaders(),
                rule.getAddHeaders(), rule.getRemoveHeaders());
        if (!headers.isEmpty()) {
            httpRoute.put("headers", headers);
        }

        if (rule.getTimeout() != null) {
            httpRoute.put("timeout", rule.getTimeout() + "ms");
        }
        if (rule.getIdleTimeout() != null) {
            httpRoute.put("idleTimeout", rule.getIdleTimeout() + "ms");
        }

        return httpRoute;
    }

    private Map<String, Object> buildHeaders(List<HeaderDefinition> vsAdd,
                                             List<String> vsRemove,
                                             List<HeaderDefinition> ruleAdd,
                                             List<String> ruleRemove) {
        Map<String, Object> headers = new LinkedHashMap<>();
        Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> add = new LinkedHashMap<>();

        for (HeaderDefinition header : Optional.ofNullable(vsAdd).orElse(List.of())) {
            add.put(header.getName(), header.getValue());
        }
        for (HeaderDefinition header : Optional.ofNullable(ruleAdd).orElse(List.of())) {
            add.put(header.getName(), header.getValue());
        }
        if (!add.isEmpty()) {
            request.put("add", add);
        }

        List<String> removeHeaders = new ArrayList<>();
        removeHeaders.addAll(Optional.ofNullable(vsRemove).orElse(List.of()));
        removeHeaders.addAll(Optional.ofNullable(ruleRemove).orElse(List.of()));
        if (!removeHeaders.isEmpty()) {
            request.put("remove", removeHeaders);
        }

        if (!request.isEmpty()) {
            headers.put("request", request);
        }
        return headers;
    }

    private Map<String, Object> buildDestinationRule(VirtualService virtualService,
                                                     RoutingConfigRequestV3 routingConfig,
                                                     RouteV3 route,
                                                     String suffix,
                                                     Map<String, Object> crMetadata) {
        RouteDestination destination = route.getDestination();

        Map<String, Object> dr = new LinkedHashMap<>();
        dr.put("apiVersion", "networking.istio.io/v1beta1");
        dr.put("kind", "DestinationRule");

        Map<String, Object> metadata = buildMetadata(virtualService.getName() + "-dr" + suffix,
                routingConfig.getNamespace(), crMetadata);
        dr.put("metadata", metadata);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("host", destination.getCluster());

        Map<String, Object> trafficPolicy = new LinkedHashMap<>();
        Map<String, Object> tls = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(destination.getTlsSupported())) {
            tls.put("mode", "SIMPLE");
            if (destination.getTlsConfigName() != null) {
                tls.put("credentialName", destination.getTlsConfigName());
            }
        }
        if (!tls.isEmpty()) {
            trafficPolicy.put("tls", tls);
        }

        Map<String, Object> connectionPool = new LinkedHashMap<>();
        Map<String, Object> tcp = new LinkedHashMap<>();
        Map<String, Object> tcpKeepAlive = buildTcpKeepAlive(destination.getTcpKeepalive());
        if (!tcpKeepAlive.isEmpty()) {
            tcp.put("tcpKeepalive", tcpKeepAlive);
        }
        CircuitBreaker circuitBreaker = destination.getCircuitBreaker();
        if (circuitBreaker != null && circuitBreaker.getThreshold() != null && circuitBreaker.getThreshold().getMaxConnections() > 0) {
            tcp.put("maxConnections", circuitBreaker.getThreshold().getMaxConnections());
        }
        if (!tcp.isEmpty()) {
            connectionPool.put("tcp", tcp);
        }

        Map<String, Object> http = new LinkedHashMap<>();
        Rule firstRule = Optional.ofNullable(route.getRules()).orElse(List.of()).stream().findFirst().orElse(null);
        if (firstRule != null && firstRule.getIdleTimeout() != null) {
            http.put("idleTimeout", firstRule.getIdleTimeout() + "ms");
        }
        if (!http.isEmpty()) {
            connectionPool.put("http", http);
        }
        if (!connectionPool.isEmpty()) {
            trafficPolicy.put("connectionPool", connectionPool);
        }

        StatefulSession session = firstRule != null ? firstRule.getStatefulSession() : null;
        Map<String, Object> statefulSessionPolicy = buildStatefulSession(session);
        trafficPolicy.putAll(statefulSessionPolicy);

        if (!trafficPolicy.isEmpty()) {
            spec.put("trafficPolicy", trafficPolicy);
        }

        dr.put("spec", spec);
        return dr;
    }

    private Map<String, Object> buildTcpKeepAlive(TcpKeepalive tcpKeepalive) {
        Map<String, Object> tcpKeepAlive = new LinkedHashMap<>();
        if (tcpKeepalive == null) {
            return tcpKeepAlive;
        }
        if (tcpKeepalive.getProbes() != null) {
            tcpKeepAlive.put("probes", tcpKeepalive.getProbes());
        }
        if (tcpKeepalive.getTime() != null) {
            tcpKeepAlive.put("time", tcpKeepalive.getTime());
        }
        if (tcpKeepalive.getInterval() != null) {
            tcpKeepAlive.put("interval", tcpKeepalive.getInterval());
        }
        return tcpKeepAlive;
    }

    private Map<String, Object> buildStatefulSession(StatefulSession session) {
        Map<String, Object> policy = new LinkedHashMap<>();
        if (session == null || Boolean.FALSE.equals(session.getEnabled())) {
            return policy;
        }
        Map<String, Object> consistentHash = new LinkedHashMap<>();
        Cookie cookie = session.getCookie();
        if (cookie != null) {
            Map<String, Object> cookieMap = new LinkedHashMap<>();
            cookieMap.put("name", cookie.getName());
            if (cookie.getPath() != null) {
                cookieMap.put("path", cookie.getPath());
            }
            if (cookie.getTtl() != null) {
                cookieMap.put("ttl", cookie.getTtl());
            }
            consistentHash.put("httpCookie", cookieMap);
        } else {
            consistentHash.put("useSourceIp", true);
        }

        Map<String, Object> loadBalancer = new LinkedHashMap<>();
        loadBalancer.put("consistentHash", consistentHash);

        if (session.getPort() != null) {
            Map<String, Object> portSetting = new LinkedHashMap<>();
            portSetting.put("port", Map.of("number", session.getPort()));
            portSetting.put("loadBalancer", loadBalancer);
            policy.put("portLevelSettings", List.of(portSetting));
        } else {
            policy.put("loadBalancer", loadBalancer);
        }

        return policy;
    }

    private Optional<Map<String, Object>> buildServiceEntry(VirtualService virtualService,
                                                            RoutingConfigRequestV3 routingConfig,
                                                            RouteV3 route,
                                                            String suffix,
                                                            Map<String, Object> crMetadata) {
        RouteDestination destination = route.getDestination();
        Endpoint endpoint = parseEndpoint(Optional.ofNullable(destination.getTlsEndpoint()).orElse(destination.getEndpoint()));
        if (endpoint.host() == null) {
            return Optional.empty();
        }

        Map<String, Object> se = new LinkedHashMap<>();
        se.put("apiVersion", "networking.istio.io/v1beta1");
        se.put("kind", "ServiceEntry");

        Map<String, Object> metadata = buildMetadata(virtualService.getName() + "-se" + suffix,
                routingConfig.getNamespace(), crMetadata);
        se.put("metadata", metadata);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("hosts", List.of(endpoint.host()));
        spec.put("location", "MESH_EXTERNAL");

        Map<String, Object> port = new LinkedHashMap<>();
        port.put("number", endpoint.port() != null ? endpoint.port() : routingConfig.getListenerPort());
        port.put("name", Boolean.TRUE.equals(destination.getTlsSupported()) ? "https" : "http");
        port.put("protocol", Boolean.TRUE.equals(destination.getTlsSupported()) ? "TLS" : "HTTP");
        spec.put("ports", List.of(port));

        Map<String, Object> endpointMap = new LinkedHashMap<>();
        endpointMap.put("address", endpoint.host());
        if (endpoint.port() != null) {
            endpointMap.put("ports", Map.of("https", endpoint.port()));
        }
        spec.put("endpoints", List.of(endpointMap));

        se.put("spec", spec);
        return Optional.of(se);
    }

    private Map<String, Object> buildMetadata(String name, String namespace, Map<String, Object> crMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object labels = crMetadata.get("labels");
        if (labels instanceof Map<?, ?> labelsMap && !labelsMap.isEmpty()) {
            metadata.put("labels", new LinkedHashMap<>((Map<?, ?>) labelsMap));
        }
        Object annotations = crMetadata.get("annotations");
        if (annotations instanceof Map<?, ?> annotationsMap && !annotationsMap.isEmpty()) {
            metadata.put("annotations", new LinkedHashMap<>((Map<?, ?>) annotationsMap));
        }
        metadata.put("name", name);
        if (namespace != null) {
            metadata.put("namespace", namespace);
        }
        return metadata;
    }

    private Endpoint parseEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return new Endpoint(null, null);
        }
        String withoutScheme = endpoint;
        int schemeIndex = endpoint.indexOf("://");
        if (schemeIndex >= 0) {
            withoutScheme = endpoint.substring(schemeIndex + 3);
        }

        int pathIndex = withoutScheme.indexOf('/') >= 0 ? withoutScheme.indexOf('/') : withoutScheme.length();
        String hostPort = withoutScheme.substring(0, pathIndex);

        String port = null;
        int lastColon = hostPort.lastIndexOf(':');
        if (lastColon > 0 && lastColon < hostPort.length() - 1) {
            port = hostPort.substring(lastColon + 1);
            hostPort = hostPort.substring(0, lastColon);
        }

        return new Endpoint(hostPort.isBlank() ? null : hostPort, port);
    }

    private void writeManifests(List<Map<String, Object>> manifests) {
        if (manifests.isEmpty()) {
            return;
        }
        Path output = Path.of(OUTPUT_FILE);
        try (Writer writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            outputYaml().dumpAll(manifests.iterator(), writer);
        } catch (IOException e) {
            log.error("Failed to write manifests to {}", output.toAbsolutePath(), e);
        }
    }

    private record Endpoint(String host, String port) {
    }
}
