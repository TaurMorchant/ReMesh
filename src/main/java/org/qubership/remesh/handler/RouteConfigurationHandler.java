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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class RouteConfigurationHandler implements CrHandler {
    private static final Yaml routingConfigYaml = createRoutingConfigYaml();

    @Override
    public String getKind() {
        return "RouteConfiguration";
    }

    public void handle(String specDump, String metadataDump) {
        RoutingConfigRequestV3 routingConfig = routingConfigYaml.load(specDump);
        if (routingConfig == null) {
            log.warn("Failed to parse RouteConfiguration fragment");
            return;
        }

        Map<String, Object> metadata = metadataDump != null ? new Yaml().load(metadataDump) : Map.of();
        if (routingConfig.getNamespace() == null && metadata.get("namespace") instanceof String namespace) {
            routingConfig.setNamespace(namespace);
        }

        List<Map<String, Object>> manifests = new ArrayList<>();
        List<VirtualService> virtualServices = Optional.ofNullable(routingConfig.getVirtualServices()).orElse(List.of());

        for (VirtualService virtualService : virtualServices) {
            manifests.add(buildVirtualService(virtualService, routingConfig));
            RouteConfig routeConfiguration = virtualService.getRouteConfiguration();
            List<RouteV3> routes = routeConfiguration != null ? Optional.ofNullable(routeConfiguration.getRoutes()).orElse(List.of()) : List.of();
            AtomicInteger destinationCounter = new AtomicInteger();

            for (RouteV3 route : routes) {
                int index = destinationCounter.incrementAndGet();
                String suffix = "-" + index;
                manifests.add(buildDestinationRule(virtualService, routingConfig, route, suffix));
                buildServiceEntry(virtualService, routingConfig, route, suffix).ifPresent(manifests::add);
            }
        }

        writeManifests(manifests);
        log.info("Generated {} Istio manifest(s) from RouteConfiguration", manifests.size());
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

    private Map<String, Object> buildVirtualService(VirtualService virtualService,
                                                    RoutingConfigRequestV3 routingConfig) {
        Map<String, Object> vs = new LinkedHashMap<>();
        vs.put("apiVersion", "networking.istio.io/v1beta1");
        vs.put("kind", "VirtualService");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", virtualService.getName());
        metadata.put("namespace", routingConfig.getNamespace());
        vs.put("metadata", metadata);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("hosts", Optional.ofNullable(virtualService.getHosts()).orElse(List.of()));
        spec.put("gateways", Optional.ofNullable(routingConfig.getGateways()).orElse(List.of()));

        List<Map<String, Object>> httpRoutes = new ArrayList<>();
        RouteConfig routeConfiguration = virtualService.getRouteConfiguration();
        List<RouteV3> routes = routeConfiguration != null ? Optional.ofNullable(routeConfiguration.getRoutes()).orElse(List.of()) : List.of();

        for (RouteV3 route : routes) {
            RouteDestination destination = route.getDestination();
            List<Rule> rules = Optional.ofNullable(route.getRules()).orElse(List.of());
            for (Rule rule : rules) {
                httpRoutes.add(buildHttpRoute(rule, destination, virtualService));
            }
        }

        spec.put("http", httpRoutes);
        vs.put("spec", spec);
        return vs;
    }

    private Map<String, Object> buildHttpRoute(Rule rule, RouteDestination destination, VirtualService virtualService) {
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
        Integer port = endpoint.port();
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

        Map<String, Object> headers = buildHeaders(virtualService.getAddHeaders(), virtualService.getRemoveHeaders(), rule.getAddHeaders(), rule.getRemoveHeaders());
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
                                                     String suffix) {
        RouteDestination destination = route.getDestination();

        Map<String, Object> dr = new LinkedHashMap<>();
        dr.put("apiVersion", "networking.istio.io/v1beta1");
        dr.put("kind", "DestinationRule");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", virtualService.getName() + "-dr" + suffix);
        metadata.put("namespace", routingConfig.getNamespace());
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
                                                            String suffix) {
        RouteDestination destination = route.getDestination();
        Endpoint endpoint = parseEndpoint(Optional.ofNullable(destination.getTlsEndpoint()).orElse(destination.getEndpoint()));
        if (endpoint.host() == null) {
            return Optional.empty();
        }

        Map<String, Object> se = new LinkedHashMap<>();
        se.put("apiVersion", "networking.istio.io/v1beta1");
        se.put("kind", "ServiceEntry");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", virtualService.getName() + "-se" + suffix);
        metadata.put("namespace", routingConfig.getNamespace());
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

    private Endpoint parseEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return new Endpoint(null, null);
        }
        try {
            URI uri = endpoint.contains("://") ? new URI(endpoint) : new URI("//" + endpoint);
            String host = uri.getHost();
            int port = uri.getPort();
            return new Endpoint(host, port >= 0 ? port : null);
        } catch (URISyntaxException e) {
            log.warn("Failed to parse endpoint {}", endpoint, e);
            return new Endpoint(endpoint, null);
        }
    }

    private void writeManifests(List<Map<String, Object>> manifests) {
        if (manifests.isEmpty()) {
            return;
        }
        Path output = Path.of("generated-istio.yaml_new");
        try (Writer writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            outputYaml().dumpAll(manifests.iterator(), writer);
        } catch (IOException e) {
            log.error("Failed to write Istio manifests to {}", output.toAbsolutePath(), e);
        }
    }

    private record Endpoint(String host, Integer port) {
    }
}
