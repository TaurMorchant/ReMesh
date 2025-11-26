package org.qubership.remesh.dto.gatewayapi;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

//generatord from https://gateway-api.sigs.k8s.io/reference/1.3/spec/?utm_source=chatgpt.com#httproute
@Data
@NoArgsConstructor
public class HttpRoute {

    private String apiVersion = "gateway.networking.k8s.io/v1";
    private String kind = "HTTPRoute";
    private ObjectMeta metadata;
    private HttpRouteSpec spec;
    private Status status;

    /* =========================
       Metadata
       ========================= */
    @Data
    @NoArgsConstructor
    public static class ObjectMeta {
        private String name;
        private String namespace;
        private Map<String, String> labels;
        private Map<String, String> annotations;
    }

    /* =========================
       Spec
       ========================= */
    @Data
    @NoArgsConstructor
    public static class HttpRouteSpec {
        private List<ParentReference> parentRefs;
        private List<String> hostnames;
        private List<Rule> rules;
    }

    /* =========================
       ParentRef
       ========================= */
    @Data
    @NoArgsConstructor
    public static class ParentReference {
        private String group;
        private String kind;
        private String name;
        private String namespace;
        private String sectionName;
        private Integer port;
    }

    /* =========================
       Rules
       ========================= */
    @Data
    @NoArgsConstructor
    public static class Rule {
        private String name;
        private List<Match> matches;
        private List<Filter> filters;
        private List<BackendRef> backendRefs;
    }

    /* =========================
       Match
       ========================= */
    @Data
    @NoArgsConstructor
    public static class Match {
        private PathMatch path;
        private List<HeaderMatch> headers;
        private List<QueryParamMatch> queryParams;
        private String method;
    }

    @Data
    @NoArgsConstructor
    public static class PathMatch {
        private PathMatchType type;
        private String value;
    }

    public enum PathMatchType {
        Exact,
        Prefix,
        RegularExpression
    }

    @Data
    @NoArgsConstructor
    public static class HeaderMatch {
        private String name;
        private HeaderMatchType type;
        private String value;
    }

    public enum HeaderMatchType {
        Exact,
        RegularExpression
    }

    @Data
    @NoArgsConstructor
    public static class QueryParamMatch {
        private String name;
        private QueryParamMatchType type;
        private String value;
    }

    public enum QueryParamMatchType {
        Exact,
        RegularExpression
    }

    /* =========================
       Filters
       ========================= */
    @Data
    @NoArgsConstructor
    public static class Filter {
        private FilterType type;

        // exactly one should be set
        private RequestHeaderModifier requestHeaderModifier;
        private ResponseHeaderModifier responseHeaderModifier;
        private RequestRedirect requestRedirect;
        private URLRewrite urlRewrite;
    }

    public enum FilterType {
        RequestHeaderModifier,
        ResponseHeaderModifier,
        RequestRedirect,
        URLRewrite
    }

    @Data
    @NoArgsConstructor
    public static class RequestHeaderModifier {
        private List<Header> add;
        private List<Header> set;
        private List<String> remove;
    }

    @Data
    @NoArgsConstructor
    public static class ResponseHeaderModifier {
        private List<Header> add;
        private List<Header> set;
        private List<String> remove;
    }

    @Data
    @NoArgsConstructor
    public static class Header {
        private String name;
        private String value;
    }

    @Data
    @NoArgsConstructor
    public static class RequestRedirect {
        private String scheme;
        private String hostname;
        private Integer port;
        private Integer statusCode;
        private PathRewrite path;
    }

    @Data
    @NoArgsConstructor
    public static class URLRewrite {
        private PathRewrite path;
        private String hostname;
    }

    @Data
    @NoArgsConstructor
    public static class PathRewrite {
        private PathRewriteType type;
        private String replaceFullPath;
        private String replacePrefixMatch;
    }

    public enum PathRewriteType {
        ReplaceFullPath,
        ReplacePrefixMatch
    }

    /* =========================
       BackendRefs
       ========================= */
    @Data
    @NoArgsConstructor
    public static class BackendRef {
        private String group;
        private String kind;
        private String name;
        private String namespace;
        private String port;
        private Integer weight;
    }

    /* =========================
       Status
       ========================= */
    @Data
    @NoArgsConstructor
    public static class Status {
        private List<ParentStatus> parents;
    }

    @Data
    @NoArgsConstructor
    public static class ParentStatus {
        private ParentReference parentRef;
        private List<RouteCondition> conditions;
    }

    @Data
    @NoArgsConstructor
    public static class RouteCondition {
        private String type;    // Accepted, ResolvedRefs, ...
        private String status;  // True / False / Unknown
        private String reason;
        private String message;
    }
}

