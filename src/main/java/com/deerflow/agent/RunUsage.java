package com.deerflow.agent;

/** 单次 run 的 token 用量（由 UsageTrackingAdvisor 累加）。 */
public class RunUsage {

    private volatile Long inputTokens;
    private volatile Long outputTokens;

    public void set(Long input, Long output) {
        this.inputTokens = input;
        this.outputTokens = output;
    }

    public Long inputTokens() { return inputTokens; }
    public Long outputTokens() { return outputTokens; }

    public boolean exceeds(Long limit) {
        if (limit == null) return false;
        long sum = (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens);
        return sum > limit;
    }
}
