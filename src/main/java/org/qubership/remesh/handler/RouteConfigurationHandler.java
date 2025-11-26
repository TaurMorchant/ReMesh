package org.qubership.remesh.handler;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.qubership.remesh.dto.*;
import org.qubership.remesh.dto.gatewayapi.HttpRoute;
import org.qubership.remesh.util.EndpointDTO;
import org.qubership.remesh.util.EndpointParser;
import org.qubership.remesh.validation.HttpRouteValidator;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
public class RouteConfigurationHandler implements CrHandler {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Override
    public String getKind() {
        return "RouteConfiguration";
    }

    @Override
    public void handle(JsonNode node, Path outputFile) {
        try {
            RouteConfigurationYaml original = YAML.treeToValue(node, RouteConfigurationYaml.class);

            List<HttpRoute> httpRoutes = new ArrayList<>();

            if (original.getSpec() != null && original.getSpec().getVirtualServices() != null) {
                for (VirtualService vs : original.getSpec().getVirtualServices()) {
                    HttpRoute httpRoute = toHttpRoute(original, vs);
                    httpRoutes.add(httpRoute);
                }
            }

            try (Writer writer = Files.newBufferedWriter(outputFile)) {
                for (HttpRoute hr : httpRoutes) {
                    JsonNode httpRouteNode = YAML.valueToTree(hr);
                    log.info("Validate HttpRoute {}", hr.getMetadata().getName());
                    HttpRouteValidator.validate(httpRouteNode);

                    writer.write("---\n");
                    writer.write(YAML.writeValueAsString(hr));
                }
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpRoute toHttpRoute(RouteConfigurationYaml routeConfiguration, VirtualService virtualService) {
        HttpRoute httpRoute = new HttpRoute();

        HttpRoute.Metadata metadata = new HttpRoute.Metadata();
        metadata.setName(safeName(routeConfiguration.getMetadata()));
        if (routeConfiguration.getMetadata() != null) {
            metadata.setNamespace(routeConfiguration.getMetadata().getNamespace());
        }
        httpRoute.setMetadata(metadata);

        HttpRoute.HttpRouteSpec spec = new HttpRoute.HttpRouteSpec();

        List<HttpRoute.ParentReference> parentRefs = gatewaysToParentReferences(routeConfiguration);
        spec.setParentRefs(parentRefs);

        List<String> hosts = virtualService.getHosts();
        if (hosts != null && !hosts.isEmpty()) {
            if (hosts.size() == 1 && hosts.getFirst().equals("*")) {
                //do not specify at all
            }
            else {
                spec.setHostnames(hosts);
            }
        }

        // rules — flatten RouteConfig.Routes[*].Rules[*]
        List<HttpRoute.Rule> rules = new ArrayList<>();
        RouteConfig routeConfig = virtualService.getRouteConfiguration();
        if (routeConfig != null && routeConfig.getRoutes() != null) {
            for (RouteV3 r : routeConfig.getRoutes()) {
                for (Rule rule : r.getRules()) {
                    if (rule.getDeny() != null && Boolean.TRUE.equals(rule.getDeny())) {
                        continue;
                    }
                    if (rule.getAllowed() != null && Boolean.FALSE.equals(rule.getAllowed())) {
                        continue;
                    }

                    HttpRoute.Rule newRule = new HttpRoute.Rule();

                    // match
                    List<HttpRoute.Match> matches = new ArrayList<>();
                    HttpRoute.Match match = toMatch(rule.getMatch());
                    if (match != null) {
                        matches.add(match);
                    }
                    if (!matches.isEmpty()) {
                        newRule.setMatches(matches);
                    }

                    // filters: URLRewrite + headers
                    List<HttpRoute.Filter> filters = getFilters(rule);

                    if (!filters.isEmpty()) {
                        newRule.setFilters(filters);
                    }

                    // backendRefs из RouteDestination
                    List<HttpRoute.BackendRef> backendRefs = new ArrayList<>();
                    HttpRoute.BackendRef backendRef = toBackendRef(r.getDestination());
                    if (backendRef != null) {
                        backendRefs.add(backendRef);
                    }
                    if (!backendRefs.isEmpty()) {
                        newRule.setBackendRefs(backendRefs);
                    }

                    rules.add(newRule);
                }
            }
        }

        spec.setRules(rules);
        httpRoute.setSpec(spec);
        return httpRoute;
    }

    private List<HttpRoute.Filter> getFilters(Rule rule) {
        List<HttpRoute.Filter> filters = new ArrayList<>();

        if (rule.getPrefixRewrite() != null && !rule.getPrefixRewrite().isEmpty()) {
            HttpRoute.Filter filter = new HttpRoute.Filter();
            filter.setType(HttpRoute.FilterType.URLRewrite);
            HttpRoute.URLRewrite urlRewrite = new HttpRoute.URLRewrite();
            HttpRoute.PathRewrite pathRewrite = new HttpRoute.PathRewrite();
            pathRewrite.setType(HttpRoute.PathRewriteType.ReplacePrefixMatch);
            pathRewrite.setReplacePrefixMatch(rule.getPrefixRewrite());
            urlRewrite.setPath(pathRewrite);
            if (rule.getHostRewrite() != null && !rule.getHostRewrite().isEmpty()) {
                urlRewrite.setHostname(rule.getHostRewrite());
            }
            filter.setUrlRewrite(urlRewrite);
            filters.add(filter);
        } else if (rule.getHostRewrite() != null && !rule.getHostRewrite().isEmpty()) {
            HttpRoute.Filter filter = new HttpRoute.Filter();
            filter.setType(HttpRoute.FilterType.URLRewrite);
            HttpRoute.URLRewrite urlRewrite = new HttpRoute.URLRewrite();
            urlRewrite.setHostname(rule.getHostRewrite());
            filter.setUrlRewrite(urlRewrite);
            filters.add(filter);
        }

        if ((rule.getAddHeaders() != null && !rule.getAddHeaders().isEmpty())
            || (rule.getRemoveHeaders() != null && !rule.getRemoveHeaders().isEmpty())) {
            HttpRoute.Filter filter = new HttpRoute.Filter();
            filter.setType(HttpRoute.FilterType.RequestHeaderModifier);
            HttpRoute.RequestHeaderModifier requestHeaderModifier = new HttpRoute.RequestHeaderModifier();

            if (rule.getAddHeaders() != null) {
                List<HttpRoute.Header> add = new ArrayList<>();
                for (HeaderDefinition headerDefinition : rule.getAddHeaders()) {
                    HttpRoute.Header header = new HttpRoute.Header();
                    header.setName(headerDefinition.getName());
                    header.setValue(headerDefinition.getValue());
                    add.add(header);
                }
                requestHeaderModifier.setAdd(add);
            }
            if (rule.getRemoveHeaders() != null) {
                requestHeaderModifier.setRemove(new ArrayList<>(rule.getRemoveHeaders()));
            }
            filter.setRequestHeaderModifier(requestHeaderModifier);
            filters.add(filter);
        }
        return filters;

        // TODO: vs.RateLimit, rule.RateLimit → extensionRef / EnvoyFilter
        // TODO: Timeout / IdleTimeout → implementation-specific policy
        // TODO: LuaFilter → EnvoyFilter
        // TODO: StatefulSession → DestinationRule (sticky sessions)
    }

    private List<HttpRoute.ParentReference> gatewaysToParentReferences(RouteConfigurationYaml routeConfiguration) {
        List<HttpRoute.ParentReference> parentRefs = new ArrayList<>();
        if (routeConfiguration.getSpec() != null && routeConfiguration.getSpec().getGateways() != null) {
            for (String gateway : routeConfiguration.getSpec().getGateways()) {
                HttpRoute.ParentReference parentReference = new HttpRoute.ParentReference();
                parentReference.setGroup("gateway.networking.k8s.io");
                parentReference.setKind("Gateway");
                parentReference.setName(gateway);
                parentRefs.add(parentReference);
            }
        }
        return parentRefs;
    }

    private String safeName(Metadata metadata) {
        if (metadata != null && metadata.getName() != null && !metadata.getName().isEmpty()) {
            return metadata.getName() + "-http-route";
        }
        return "generated-http-route";
    }

    private HttpRoute.Match toMatch(RouteMatch match) {
        if (match == null) {
            return null;
        }
        HttpRoute.Match result = new HttpRoute.Match();

        // path
        if (match.getPrefix() != null && !match.getPrefix().isEmpty()) {
            HttpRoute.PathMatch pathMatch = new HttpRoute.PathMatch();
            pathMatch.setType(HttpRoute.PathMatchType.PathPrefix);
            pathMatch.setValue(match.getPrefix());
            result.setPath(pathMatch);
        } else if (match.getPath() != null && !match.getPath().isEmpty()) {
            HttpRoute.PathMatch pathMatch = new HttpRoute.PathMatch();
            pathMatch.setType(HttpRoute.PathMatchType.Exact);
            pathMatch.setValue(match.getPath());
            result.setPath(pathMatch);
        } else if (match.getRegExp() != null && !match.getRegExp().isEmpty()) {
            HttpRoute.PathMatch pathMatch = new HttpRoute.PathMatch();
            pathMatch.setType(HttpRoute.PathMatchType.RegularExpression);
            pathMatch.setValue(match.getRegExp());
            result.setPath(pathMatch);
        }

        // headers
        if (match.getHeaderMatchers() != null && !match.getHeaderMatchers().isEmpty()) {
            List<HttpRoute.HeaderMatch> headers = getHeaderMatches(match);
            result.setHeaders(headers);
        }

        if (result.getPath() == null && (result.getHeaders() == null || result.getHeaders().isEmpty())) {
            return null;
        }
        return result;
    }

    private List<HttpRoute.HeaderMatch> getHeaderMatches(RouteMatch match) {
        List<HttpRoute.HeaderMatch> headers = new ArrayList<>();
        for (HeaderMatcher headerMatcher : match.getHeaderMatchers()) {
            HttpRoute.HeaderMatch headerMatch = new HttpRoute.HeaderMatch();
            headerMatch.setName(headerMatcher.getName());
            if (headerMatcher.getExactMatch() != null) {
                headerMatch.setType(HttpRoute.HeaderMatchType.Exact);
                headerMatch.setValue(headerMatcher.getExactMatch());
            }
            else if (headerMatcher.getSafeRegexMatch() != null) {
                headerMatch.setType(HttpRoute.HeaderMatchType.RegularExpression);
                headerMatch.setValue(headerMatcher.getSafeRegexMatch());
            } else if (headerMatcher.getPrefixMatch() != null) {
                headerMatch.setType(HttpRoute.HeaderMatchType.RegularExpression);
                headerMatch.setValue("^" + Pattern.quote(headerMatcher.getSafeRegexMatch()) + ".*$");
            }
            else if (headerMatcher.getSuffixMatch() != null) {
                headerMatch.setType(HttpRoute.HeaderMatchType.RegularExpression);
                headerMatch.setValue(".*" + Pattern.quote(headerMatcher.getSafeRegexMatch()) + "$");
            }
            else if (headerMatcher.isPresentMatch()) {
                headerMatch.setType(HttpRoute.HeaderMatchType.RegularExpression);
                headerMatch.setValue(".*");
            }
            else
            {
                //TODO VLLA остальные типы не поддерживаются в чистом виде
                log.warn("Header match {} is unsupported", headerMatch);
            }

            headers.add(headerMatch);
        }
        return headers;
    }

    private HttpRoute.BackendRef toBackendRef(RouteDestination dst) {
        if (dst == null) {
            return null;
        }
        EndpointDTO endpoint = EndpointParser.parse(dst.getEndpoint());

        HttpRoute.BackendRef backendRef = new HttpRoute.BackendRef();
        backendRef.setKind("Service");
        backendRef.setName(endpoint.getHost());
        backendRef.setPort(endpoint.getPort());
        // TODO: dst.TlsSupported / TlsEndpoint / HttpVersion / TlsConfigName → DestinationRule/Policy

        return backendRef;
    }
}
