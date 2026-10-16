package com.xinghuiTec.domain.vo;

public record ConfigView(
        Integer configId,
        String configKey,
        String configName,
        String configValue,
        boolean runtimeActive,
        boolean deletable) {
}
