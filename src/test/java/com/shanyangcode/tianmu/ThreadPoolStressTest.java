package com.orangecode.tianmu;

import com.orangecode.tianmu.common.ThreadPoolMonitor;
import com.orangecode.tianmu.model.dto.file.InitUploadRequest;
import com.orangecode.tianmu.service.FileService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class ThreadPoolStressTest {

    @Autowired
    private ThreadPoolExecutor uploadThreadPool;

    @Autowired
    private FileService fileService;

    // 测试不同任务数量：10, 100, 1000, 5000
    @ParameterizedTest
    @ValueSource(ints = {10, 100, 1000, 5000})
    void testUploadUrlsWithDifferentChunkCounts(int chunkCount) throws Exception {
        // 重置线程池（确保每次测试独立）
        uploadThreadPool.prestartAllCoreThreads();

        // 模拟请求
        InitUploadRequest request = new InitUploadRequest();
        request.setFileHash("test_" + System.currentTimeMillis());
        request.setChunkCount(chunkCount);

        // 记录开始时间
        long startTime = System.currentTimeMillis();
        ThreadPoolMonitor.printStats(uploadThreadPool, "开始前");

        // 执行测试
        List<String> urls = fileService.getUploadUrls(request);

        // 输出结果
        System.out.printf("任务数量: %d | 耗时: %dms | 实际生成URL数: %d%n",
                chunkCount,
                System.currentTimeMillis() - startTime,
                urls.size());
        ThreadPoolMonitor.printStats(uploadThreadPool, "完成后");
        System.out.println();

        // 建议：此处添加断言
        assertEquals(chunkCount, urls.size());
    }
}
