package com.deerflow.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class WebSearchTool {

    private final RestClient http;
    private final String apiKey;

    public WebSearchTool(RestClient.Builder builder,
                         @Value("${deerflow.tavily.api-key:${TAVILY_API_KEY:}}") String apiKey) {
        this.http = builder.baseUrl("https://api.tavily.com").build();
        this.apiKey = apiKey;
    }

    @SuppressWarnings("unchecked")
    public String webSearch(String query, int maxResults) {
        Map<String, Object> body = Map.of(
                "api_key", apiKey,
                "query", query,
                "max_results", Math.max(1, Math.min(maxResults, 10)));
        Map<String, Object> resp = http.post().uri("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
        if (results == null || results.isEmpty()) {
            return "未找到相关结果";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : results) {
            sb.append("- ").append(r.get("title")).append("\n  ")
              .append(r.get("url")).append("\n  ")
              .append(r.get("content")).append("\n");
        }
        return sb.toString();
    }
}
