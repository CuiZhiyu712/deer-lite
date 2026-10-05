package com.deerflow.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class DemoController {

    private final ChatClient chatClient;

    public DemoController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping(value = "/api/demo/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String q) {
        return chatClient.prompt().user(q).stream().content();
    }
}
