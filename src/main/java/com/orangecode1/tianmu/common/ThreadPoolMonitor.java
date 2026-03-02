package com.orangecode.tianmu.common;

import java.util.concurrent.ThreadPoolExecutor;

public class ThreadPoolMonitor {
    public static void printStats(ThreadPoolExecutor executor, String phase) {
        System.out.printf("[%s] 线程池状态: " +
                        "核心线程数=%d, 活动线程数=%d, 最大线程数=%d, " +
                        "队列大小=%d/%d, 完成任务数=%d%n",
                phase,
                executor.getCorePoolSize(),
                executor.getActiveCount(),
                executor.getMaximumPoolSize(),
                executor.getQueue().size(),
                executor.getQueue().remainingCapacity(),
                executor.getCompletedTaskCount());
    }
}
