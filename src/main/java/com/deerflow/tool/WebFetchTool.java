package com.deerflow.tool;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

@Component
public class WebFetchTool {

    private final RestClient http;

    public WebFetchTool(RestClient.Builder builder) {
        this.http = builder.baseUrl("https://r.jina.ai").build();
    }

    public String webFetch(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "错误：url 必须以 http:// 或 https:// 开头";
        }
        // 用 URI 参数绕过模板变量严格编码：.uri("/{url}", url) 会把 :// 编码成 %3A%2F%2F（实测）
        String text = http.get().uri(URI.create("/" + url)).retrieve().body(String.class);
        if (text == null) {
            return "抓取失败：空响应";
        }
        return text.length() > 100_000 ? text.substring(0, 100_000) + "\n[内容截断]" : text;
    }
}
