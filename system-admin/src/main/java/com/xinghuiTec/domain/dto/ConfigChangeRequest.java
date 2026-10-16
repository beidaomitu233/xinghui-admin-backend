package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfigChangeRequest(
        @NotBlank String configKey,
        @NotBlank @Size(max = 500) String configValue) {
}
