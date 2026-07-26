package com.xinghuiTec;

import org.dromara.x.file.storage.spring.EnableFileStorage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableFileStorage
@SpringBootApplication
@EnableAsync
public class XinghuiStartApplication {
    public static void main(String[] args) {
        SpringApplication.run(XinghuiStartApplication.class, args);
    }
}
