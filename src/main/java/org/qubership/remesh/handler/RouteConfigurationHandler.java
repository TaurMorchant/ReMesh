package org.qubership.remesh.handler;

import lombok.extern.slf4j.Slf4j;
import org.qubership.remesh.dto.RoutingConfigRequestV3;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.PropertyUtils;

@Slf4j
public class RouteConfigurationHandler implements CrHandler {
    private static final Yaml routingConfigYaml = createRoutingConfigYaml();

    @Override
    public String getKind() {
        return "RouteConfiguration";
    }

    public void handle(String specDump) {
        RoutingConfigRequestV3 routingConfig = routingConfigYaml.load(specDump);
        log.info("Parsed RouteConfiguration fragment: {}", routingConfig);
    }

    private static Yaml createRoutingConfigYaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(RoutingConfigRequestV3.class, loaderOptions);
        PropertyUtils propertyUtils = new PropertyUtils();
        propertyUtils.setSkipMissingProperties(true);
        constructor.setPropertyUtils(propertyUtils);
        return new Yaml(constructor);
    }
}
