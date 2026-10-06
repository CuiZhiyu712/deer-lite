package com.deerflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** yaml `deerflow.models` 定义的多模型注册表；空列表时 clientFor 返回 null（走默认自动配置）。 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    /** 与 application.yml 中 deerflow.models[].api-key 的占位符默认值保持一致。 */
    static final String PLACEHOLDER_API_KEY = "missing-key-tell-user";

    private final Map<String, ChatClient> clients = new LinkedHashMap<>();

    public ModelRegistry(ModelsProperties props) {
        for (ModelsProperties.ModelDef def : props.getModels()) {
            String apiKey = def.getApiKey();
            // 构造 OpenAiChatModel 不校验凭据，占位符也能建出客户端、直到调用时才 401；
            // 因此这里显式跳过缺槽/占位符条目（T20 实测回归：否则测试中的脚本模型被真实客户端劫持）。
            if (apiKey == null || apiKey.isBlank() || PLACEHOLDER_API_KEY.equals(apiKey)) {
                log.warn("模型 {} 的 api-key 缺失或仍为占位符，跳过注册（设置环境变量 DEEPSEEK_API_KEY 后重启生效）", def.getName());
                continue;
            }
            try {
                // Spring AI 2.0.1：OpenAiApi 类已移除；OpenAiChatModel.Builder 在未显式提供
                // openAiClient 时按 options 内的 baseUrl/apiKey 惰性创建官方 SDK 客户端。
                OpenAiChatModel model = OpenAiChatModel.builder()
                        .options(OpenAiChatOptions.builder()
                                .model(def.getModel())
                                .baseUrl(def.getBaseUrl())
                                .apiKey(apiKey)
                                .build())
                        .build();
                clients.put(def.getName(), ChatClient.builder(model).build());
            } catch (Exception e) {
                log.warn("模型 {} 初始化失败，跳过: {}", def.getName(), e.getMessage());
            }
        }
        if (!clients.isEmpty()) {
            log.info("多模型注册表加载: {}", clients.keySet());
        }
    }

    public List<String> names() {
        return List.copyOf(clients.keySet());
    }

    /** 未命中或未配置返回 null——调用方回退默认 ChatClient.Builder。 */
    public ChatClient clientFor(String name) {
        if (name == null) {
            return null;
        }
        return clients.get(name);
    }
}
