package com.visionocr.ui.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/** Serves brand assets (logo, licensed fonts) from a local folder that is kept out of git. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final Path brandDir;

    public WebConfig(@Value("${ui.brand-dir:./brand}") String brandDir) {
        this.brandDir = Path.of(brandDir).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = brandDir.toUri().toString();
        registry.addResourceHandler("/brand/**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/")
                .setCachePeriod(3600);
    }
}
