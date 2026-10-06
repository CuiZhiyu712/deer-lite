package com.deerflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "deerflow")
public class ModelsProperties {

    private List<ModelDef> models = new ArrayList<>();

    public List<ModelDef> getModels() { return models; }
    public void setModels(List<ModelDef> models) { this.models = models; }

    public static class ModelDef {
        private String name;
        private String baseUrl;
        private String apiKey;
        private String model;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
