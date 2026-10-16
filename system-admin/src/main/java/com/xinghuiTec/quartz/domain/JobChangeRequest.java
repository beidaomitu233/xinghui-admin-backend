package com.xinghuiTec.quartz.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only managed scheduler fields are accepted from the API. */
public record JobChangeRequest(
        Long jobId,
        @NotBlank @Size(max = 64) String jobName,
        @NotBlank @Size(max = 64) String jobGroup,
        @NotBlank String handlerKey,
        String handlerParams,
        @NotBlank String cronExpression,
        @NotBlank String timeZone,
        @NotBlank String misfirePolicy,
        @NotBlank String concurrent,
        @NotBlank String status,
        @Size(max = 500) String remark) {
}
