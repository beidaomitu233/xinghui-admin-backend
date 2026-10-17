package com.xinghuiTec.ai.config;

import java.util.List;

/** Single allowlist shared by configuration, chat transport and model discovery. */
public final class AiProviderPresets {
    private AiProviderPresets() {}
    public record Preset(String code,String name,String chatEndpoint,String modelsEndpoint,List<String> suggestedModels) {}
    public static List<Preset> all() {
        return List.of(
                new Preset("openrouter","OpenRouter","https://openrouter.ai/api/v1/chat/completions","https://openrouter.ai/api/v1/models",
                        List.of("poolside/laguna-s-2.1:free","nvidia/nemotron-3.5-lightning:free")),
                new Preset("deepseek","DeepSeek","https://api.deepseek.com/chat/completions","https://api.deepseek.com/models",
                        List.of("deepseek-flash","deepseek-v4-pro")));
    }
    public static Preset require(String code) {
        return all().stream().filter(p->p.code().equals(code)).findFirst()
                .orElseThrow(()->new AiConfigurationException(400,"请选择支持的服务商"));
    }
    public static Preset require(String code,String chatEndpoint) {
        var preset=require(code);
        if(!preset.chatEndpoint().equals(chatEndpoint)) throw new AiConfigurationException(400,"请使用所选服务商的官方地址");
        return preset;
    }
}
