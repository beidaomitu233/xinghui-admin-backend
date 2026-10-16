package com.xinghuiTec.quartz.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling is enabled independently from the legacy RyTask example. */
@Configuration
@EnableScheduling
public class JobSchedulingConfiguration {
}
