package com.deerflow.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRegistryTest {

    @Test
    void emptyRegistryIsNoOp() {
        var registry = new ModelRegistry(new ModelsProperties());
        assertThat(registry.names()).isEmpty();
        assertThat(registry.clientFor("deepseek-chat")).isNull();
    }

    @Test
    void placeholderKeyIsSkipped() {
        var props = new ModelsProperties();
        var def = new ModelsProperties.ModelDef();
        def.setName("deepseek-chat");
        def.setBaseUrl("https://api.deepseek.com");
        def.setApiKey(ModelRegistry.PLACEHOLDER_API_KEY);
        def.setModel("deepseek-chat");
        props.setModels(List.of(def));

        var registry = new ModelRegistry(props);

        assertThat(registry.names()).isEmpty();
        assertThat(registry.clientFor("deepseek-chat")).isNull();
    }
}
