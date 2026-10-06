package com.deerflow.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WebToolsTest {

    @Test
    void tavilySearchFormatsTopResults() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.tavily.com/search"))
              .andRespond(withSuccess("""
                  {"results":[
                    {"title":"A","url":"https://a.com","content":"aaa"},
                    {"title":"B","url":"https://b.com","content":"bbb"}
                  ]}""", MediaType.APPLICATION_JSON));

        var tool = new WebSearchTool(builder, "test-key");
        String out = tool.webSearch("deerflow java", 5);
        assertThat(out).contains("A").contains("https://a.com").contains("B");
        server.verify();
    }

    @Test
    void jinaFetchReturnsMarkdownText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://r.jina.ai/https://example.com"))
              .andRespond(withSuccess("# Title\ncontent", MediaType.TEXT_PLAIN));

        var tool = new WebFetchTool(builder);
        assertThat(tool.webFetch("https://example.com")).contains("# Title");
        server.verify();
    }
}
