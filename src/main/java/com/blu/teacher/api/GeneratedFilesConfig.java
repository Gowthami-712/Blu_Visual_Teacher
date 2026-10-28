package com.blu.teacher.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.file.Paths;

@Configuration
public class GeneratedFilesConfig implements WebMvcConfigurer {
    @Value("${blu.output-dir:./generated}") private String outputDir;
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(outputDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/generated/**").addResourceLocations(location);
    }
}
