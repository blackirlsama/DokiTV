package com.orangecode.tianmu.config;

import com.orangecode.tianmu.constants.ThreadPoolExecutorConstant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
 public class ThreadPoolConfig {
     @Bean
     public ThreadPoolExecutor uploadThreadPool() {
         return new ThreadPoolExecutor(
             ThreadPoolExecutorConstant.CORE_POOL_SIZE,
             ThreadPoolExecutorConstant.MAX_POOL_SIZE,
             ThreadPoolExecutorConstant.KEEP_ALIVE_TIME,
             TimeUnit.MINUTES,
             new LinkedBlockingQueue<>(ThreadPoolExecutorConstant.QUEUE_CAPACITY),
             Executors.defaultThreadFactory(),
             new ThreadPoolExecutor.DiscardPolicy() // 任务满时拒绝策略，根据业务调整
         );
     }
 }
