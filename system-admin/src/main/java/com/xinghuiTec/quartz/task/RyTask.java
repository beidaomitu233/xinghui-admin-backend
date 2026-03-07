package com.xinghuiTec.quartz.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通用定时任务示例
 * 
 * 此模块为解耦的定时任务模块，可以直接删除而不会影响主程序运行
 */
@Component
@EnableScheduling
@Slf4j
public class RyTask {

    /**
     * 无参定时任务示例
     * 每隔1分钟执行一次
     */
//    @Scheduled(fixedRate = 60000)
//    public void ryNoParams() {
//        log.info("执行无参定时任务: ryNoParams");
//    }

    /**
     * 有参定时任务示例 (注意：@Scheduled不支持直接传参，此处仅为示例，
     * 实际动态任务通常通过反射调用，而硬编码的@Scheduled任务参数通常是固定的)
     */
//    @Scheduled(cron = "0 0/10 * * * ?")
//    public void ryParams() {
//        log.info("执行定时任务: ryParams");
//    }
}
