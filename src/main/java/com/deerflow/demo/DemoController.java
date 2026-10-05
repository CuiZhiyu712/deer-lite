package com.deerflow.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class DemoController {

    private final ChatClient chatClient;
    private final ToolCallback timeTool;

    public DemoController(ChatClient chatClient, ToolCallback timeTool) {
        this.chatClient = chatClient;
        this.timeTool = timeTool;
    }

    @GetMapping(value = "/api/demo/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String q) {
        return chatClient.prompt().user(q)
                .toolCallbacks(timeTool)
                .stream().content();
    }
}
