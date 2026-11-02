package com.xinghuiTec.ai.config;

import java.util.List;

/** Provider defaults and OpenAI-compatible endpoint resolution. */
public final class AiProviderPresets {
    private AiProviderPresets() {}
    public record Preset(String code,String name,String chatEndpoint,String modelsEndpoint,String publicModelsEndpoint,List<String> suggestedModels) {
        public Preset(String code,String name,String chatEndpoint,String modelsEndpoint,List<String> suggestedModels) {this(code,name,chatEndpoint,modelsEndpoint,null,suggestedModels);}
    }
    public static List<Preset> all() {
        return List.of(
                new Preset("openrouter","OpenRouter","https://openrouter.ai/api/v1/chat/completions","https://openrouter.ai/api/v1/models/user","https://openrouter.ai/api/v1/models",
                        List.of("poolside/laguna-s-2.1:free","nvidia/nemotron-3.5-lightning:free")),
                new Preset("deepseek","DeepSeek","https://api.deepseek.com/chat/completions","https://api.deepseek.com/models",null,
                        List.of("deepseek-flash","deepseek-v4-pro")),
                new Preset("openai","OpenAI","https://api.openai.com/v1/chat/completions","https://api.openai.com/v1/models",null,List.of()),
                new Preset("mimo","小米 MiMo","https://api.xiaomimimo.com/v1/chat/completions","https://api.xiaomimimo.com/v1/models",null,List.of("mimo-v2.6-flash")),
                new Preset("siliconflow","硅基流动","https://api.siliconflow.cn/v1/chat/completions","https://api.siliconflow.cn/v1/models?sub_type=chat",null,List.of()),
                new Preset("openai-compatible","OpenAI 兼容服务","","",null,List.of()));
    }
    public static Preset require(String code) {
        return all().stream().filter(p->p.code().equals(code)).findFirst()
                .orElseThrow(()->new AiConfigurationException(400,"请选择支持的服务商"));
    }
    public static Preset require(String code,String chatEndpoint) {
        var preset=require(code);
        if ("openai-compatible".equals(code)) {
            String endpoint = new com.xinghuiTec.ai.model.AiModelConfigRules().endpointUrl(chatEndpoint);
            while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length()-1);
            if (!endpoint.endsWith("/chat/completions")) endpoint += "/chat/completions";
            String models = endpoint.substring(0, endpoint.length()-"/chat/completions".length()) + "/models";
            return new Preset(code,preset.name(),endpoint,models,null,List.of());
        }
        if(!preset.chatEndpoint().equals(chatEndpoint)) throw new AiConfigurationException(400,"请使用所选服务商的官方地址");
        return preset;
    }
}
