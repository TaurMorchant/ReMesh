package org.qubership.remesh.handler;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.qubership.remesh.dto.*;
import org.qubership.remesh.dto.gatewayapi.HttpRoute;
import org.qubership.remesh.util.EndpointDTO;
import org.qubership.remesh.util.EndpointParser;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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
            RouteConfigurationYaml old = YAML.treeToValue(node, RouteConfigurationYaml.class);

            List<HttpRoute> httpRoutes = new ArrayList<>();

            if (old.getSpec() != null && old.getSpec().getVirtualServices() != null) {
                for (VirtualService vs : old.getSpec().getVirtualServices()) {
                    HttpRoute hr = toHttpRoute(old, vs);
                    httpRoutes.add(hr);
                }
            }

            try (Writer writer = Files.newBufferedWriter(outputFile)) {
                for (HttpRoute hr : httpRoutes) {
                    writer.write("---\n");
                    writer.write(YAML.writeValueAsString(hr));
                }
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpRoute toHttpRoute(RouteConfigurationYaml old, VirtualService vs) {
        HttpRoute hr = new HttpRoute();

        HttpRoute.ObjectMeta md = new HttpRoute.ObjectMeta();
        md.setName(safeName(old.getMetadata()));
        if (old.getMetadata() != null) {
            md.setNamespace(old.getMetadata().getNamespace());
        }
        hr.setMetadata(md);

        HttpRoute.HttpRouteSpec spec = new HttpRoute.HttpRouteSpec();

        // parentRefs из spec.gateways
        List<HttpRoute.ParentReference> parentRefs = new ArrayList<>();
        if (old.getSpec() != null && old.getSpec().getGateways() != null) {
            for (String gw : old.getSpec().getGateways()) {
                HttpRoute.ParentReference pr = new HttpRoute.ParentReference();
                pr.setGroup("gateway.networking.k8s.io");
                pr.setKind("Gateway");
                pr.setName(gw);
                // namespace можно добавить при необходимости
                parentRefs.add(pr);
            }
        }
        spec.setParentRefs(parentRefs);

        // hostnames из VirtualService.hosts
        if (vs.getHosts() != null && !vs.getHosts().isEmpty()) {
            spec.setHostnames(new ArrayList<>(vs.getHosts()));
        }

        // rules — flatten RouteConfig.Routes[*].Rules[*]
        List<HttpRoute.Rule> rules = new ArrayList<>();
        if (vs.getRouteConfiguration() != null && vs.getRouteConfiguration().getRoutes() != null) {
            for (RouteV3 r : vs.getRouteConfiguration().getRoutes()) {
                for (Rule rule : r.getRules()) {
                    // allowed/deny обработка
                    if (rule.getDeny() != null && Boolean.TRUE.equals(rule.getDeny())) {
                        // TODO: при необходимости — маппинг в directResponse/EnvoyFilter
                        continue;
                    }
                    if (rule.getAllowed() != null && !Boolean.TRUE.equals(rule.getAllowed())) {
                        // правило не разрешено — пропускаем
                        continue;
                    }

                    HttpRoute.Rule newRule = new HttpRoute.Rule();

                    // match
                    List<HttpRoute.Match> matches = new ArrayList<>();
                    HttpRoute.Match m = toMatch(rule.getMatch());
                    if (m != null) {
                        matches.add(m);
                    }
                    if (!matches.isEmpty()) {
                        newRule.setMatches(matches);
                    }

                    // filters: URLRewrite + headers
                    List<HttpRoute.Filter> filters = new ArrayList<>();

                    if (rule.getPrefixRewrite() != null && !rule.getPrefixRewrite().isEmpty()) {
                        HttpRoute.Filter f = new HttpRoute.Filter();
                        f.setType(HttpRoute.FilterType.URLRewrite);
                        HttpRoute.URLRewrite ur = new HttpRoute.URLRewrite();
                        HttpRoute.PathRewrite path = new HttpRoute.PathRewrite();
                        path.setType(HttpRoute.PathRewriteType.ReplacePrefixMatch);
                        path.setReplacePrefixMatch(rule.getPrefixRewrite());
                        ur.setPath(path);
                        if (rule.getHostRewrite() != null && !rule.getHostRewrite().isEmpty()) {
                            ur.setHostname(rule.getHostRewrite());
                        }
                        f.setUrlRewrite(ur);
                        filters.add(f);
                    } else if (rule.getHostRewrite() != null && !rule.getHostRewrite().isEmpty()) {
                        HttpRoute.Filter f = new HttpRoute.Filter();
                        f.setType(HttpRoute.FilterType.URLRewrite);
                        HttpRoute.URLRewrite ur = new HttpRoute.URLRewrite();
                        ur.setHostname(rule.getHostRewrite());
                        f.setUrlRewrite(ur);
                        filters.add(f);
                    }

                    if ((rule.getAddHeaders() != null && !rule.getAddHeaders().isEmpty())
                        || (rule.getRemoveHeaders() != null && !rule.getRemoveHeaders().isEmpty())) {
                        HttpRoute.Filter f = new HttpRoute.Filter();
                        f.setType(HttpRoute.FilterType.RequestHeaderModifier);
                        HttpRoute.RequestHeaderModifier rhm = new HttpRoute.RequestHeaderModifier();

                        if (rule.getAddHeaders() != null) {
                            List<HttpRoute.Header> add = new ArrayList<>();
                            for (HeaderDefinition hd : rule.getAddHeaders()) {
                                HttpRoute.Header h = new HttpRoute.Header();
                                h.setName(hd.getName());
                                h.setValue(hd.getValue());
                                add.add(h);
                            }
                            rhm.setAdd(add);
                        }
                        if (rule.getRemoveHeaders() != null) {
                            rhm.setRemove(new ArrayList<>(rule.getRemoveHeaders()));
                        }
                        f.setRequestHeaderModifier(rhm);
                        filters.add(f);
                    }

                    // TODO: vs.RateLimit, rule.RateLimit → extensionRef / EnvoyFilter
                    // TODO: Timeout / IdleTimeout → implementation-specific policy
                    // TODO: LuaFilter → EnvoyFilter
                    // TODO: StatefulSession → DestinationRule (sticky sessions)

                    if (!filters.isEmpty()) {
                        newRule.setFilters(filters);
                    }

                    // backendRefs из RouteDestination
                    List<HttpRoute.BackendRef> backendRefs = new ArrayList<>();
                    HttpRoute.BackendRef br = toBackendRef(r.getDestination());
                    if (br != null) {
                        backendRefs.add(br);
                    }
                    if (!backendRefs.isEmpty()) {
                        newRule.setBackendRefs(backendRefs);
                    }

                    rules.add(newRule);
                }
            }
        }

        spec.setRules(rules);
        hr.setSpec(spec);
        return hr;
    }

    private static String safeName(Metadata meta) {
        if (meta != null && meta.getName() != null && !meta.getName().isEmpty()) {
            return meta.getName() + "-http-route";
        }
        return "generated-http-route";
    }

    private static HttpRoute.Match toMatch(RouteMatch match) {
        if (match == null) {
            return null;
        }
        HttpRoute.Match res = new HttpRoute.Match();

        // path
        if (match.getPrefix() != null && !match.getPrefix().isEmpty()) {
            HttpRoute.PathMatch pm = new HttpRoute.PathMatch();
            pm.setType(HttpRoute.PathMatchType.Prefix);
            pm.setValue(match.getPrefix());
            res.setPath(pm);
        } else if (match.getPath() != null && !match.getPath().isEmpty()) {
            HttpRoute.PathMatch pm = new HttpRoute.PathMatch();
            pm.setType(HttpRoute.PathMatchType.Exact);
            pm.setValue(match.getPath());
            res.setPath(pm);
        } else if (match.getRegExp() != null && !match.getRegExp().isEmpty()) {
            // В core HTTPRoute regex нет — оставляем TODO, чтобы не терять факт наличия regex
            // TODO: маппить Regexp в Istio VirtualService или implementation-specific расширения
        }

        // headers
        if (match.getHeaderMatchers() != null && !match.getHeaderMatchers().isEmpty()) {
            List<HttpRoute.HeaderMatch> headers = new ArrayList<>();
            for (HeaderMatcher hm : match.getHeaderMatchers()) {
                HttpRoute.HeaderMatch hh = new HttpRoute.HeaderMatch();
                hh.setName(hm.getName());
                //todo vlla доделать в соответствии с нашими DTO
//                hh.setType(hm.getType());    // предполагаем, что type уже совместим (Exact/RegularExpression и т.п.)
//                hh.setValue(hm.getValue());
                headers.add(hh);
            }
            res.setHeaders(headers);
        }

        if (res.getPath() == null && (res.getHeaders() == null || res.getHeaders().isEmpty())) {
            return null;
        }
        return res;
    }

    private static HttpRoute.BackendRef toBackendRef(RouteDestination dst) {
        if (dst == null) {
            return null;
        }
        EndpointDTO endpoint = null;
        if (dst.getEndpoint() != null && !dst.getEndpoint().isEmpty()) {
            endpoint = EndpointParser.parse(dst.getEndpoint());
        }

        HttpRoute.BackendRef br = new HttpRoute.BackendRef();
        br.setKind("Service");
        br.setName(endpoint.getHost());
        br.setPort(endpoint.getPort());
        // TODO: dst.TlsSupported / TlsEndpoint / HttpVersion / TlsConfigName → DestinationRule/Policy

        return br;
    }
}

