package com.shanyangcode.tianmu.service.impl;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.constants.MinIOConstant;
import com.shanyangcode.tianmu.constants.SnowflakeConstant;
import com.shanyangcode.tianmu.constants.ThreadPoolExecutorConstant;
import com.shanyangcode.tianmu.exception.BusinessException;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import com.shanyangcode.tianmu.mapper.FileMapper;
import com.shanyangcode.tianmu.model.dto.file.InitUploadRequest;
import com.shanyangcode.tianmu.model.dto.file.MergeChunkRequest;
import com.shanyangcode.tianmu.model.entity.File;
import com.shanyangcode.tianmu.service.FileService;
import com.shanyangcode.tianmu.utils.MinioUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements FileService {
    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Resource
    private MinioUtil minioUtil;


    /**
     * 文件存在性校验（秒传核心方法）
     * 根据文件唯一哈希值查询数据库，若文件已存在则返回访问链接，实现秒传功能
     * @param fileHash 文件唯一哈希值（MD5/SHA1），必传
     * @return 存在则返回文件访问URL，不存在则返回null
     */
    @Override
    public String checkFileExistence(String fileHash) {
        if (StringUtils.isBlank(fileHash)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        // 根据文件 hash 查询文件
        LambdaQueryWrapper<File> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(File::getFileHash, fileHash);
        File file = this.getOne(queryWrapper);

        return file != null ? file.getFileUrl() : null;
    }


    /**
     * 初始化分片上传，批量生成所有分片的MinIO预签名上传URL
     * 采用CompletableFuture异步生成分片URL，提升大批量分片的生成效率
     * @param initUploadRequest 分片上传初始化请求体，包含fileHash、chunkCount等核心参数
     * @return 按分片索引顺序排列的预签名URL列表，可直接供前端直传MinIO
     */
    @Override
    public List<String> getUploadUrls(InitUploadRequest initUploadRequest) {
        // 1. 参数校验（提前暴露非法输入）
        if (initUploadRequest == null) {
            throw new IllegalArgumentException("初始化上传请求不能为空");
        }
        String fileHash = initUploadRequest.getFileHash();
        int chunkCount = initUploadRequest.getChunkCount();
        if (StringUtils.isBlank(fileHash)) {
            throw new IllegalArgumentException("文件哈希值不能为空");
        }
        if (chunkCount <= 0) {
            throw new IllegalArgumentException("分片数量必须大于0，当前值：" + chunkCount);
        }

        // 2. 使用 CompletableFuture 处理异步任务
        List<CompletableFuture<String>> futures = new ArrayList<>(chunkCount);
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            final int index = chunkIndex;
            // 提交异步任务
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                            minioUtil.uploadChunkUrl(fileHash, index, MinIOConstant.VIDEO_EXPIRE_TIME, TimeUnit.MINUTES),
                    threadPoolExecutor // 注入的线程池Bean
            ).exceptionally(e -> {
                // 异常处理：记录具体分片错误，后续统一抛出
                log.error("生成分片[{}]上传URL失败", index, e);
                throw new CompletionException("分片[" + index + "]生成URL失败", e);
            });
            futures.add(future);
        }

        // 3. 等待所有任务完成（带超时控制）
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        try {
            // 等待超时：使用线程池配置的超时时间
            allFutures.get(ThreadPoolExecutorConstant.AWAIT_TERMINATION, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("生成分片URL超时（{}秒），已完成{}个分片",
                    ThreadPoolExecutorConstant.AWAIT_TERMINATION,
                    futures.stream().filter(CompletableFuture::isDone).count());
            throw new RuntimeException("生成分片URL超时，请重试", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("生成URL操作被中断", e);
        } catch (ExecutionException e) {
            // 捕获子任务的异常（由exceptionally抛出的CompletionException）
            throw new RuntimeException("生成分片URL失败", e.getCause());
        }

        // 4. 收集结果（按分片顺序排列）
        return futures.stream()  // 把 futures 变成“流”
                .map(CompletableFuture::join) // 每个 future 调用 join()，得到结果，此时已完成，不会阻塞
                .collect(Collectors.toList()); // 把所有结果装进一个 List<String>
    }

    /**
     * 查询文件分片上传进度，获取已上传的分片索引集合
     * 业务层无额外逻辑，直接透传调用MinIO工具类的底层实现
     * @param fileHash 文件唯一哈希值
     * @return 已上传分片的索引Set集合（天然去重），未上传则返回空Set
     */
    @Override
    public Set<Integer> getUploadProgress(String fileHash) {
        return minioUtil.getChunkProgress(fileHash);
    }


    /**
     * 合并文件分片，完成分片上传最终业务流程
     * 核心：调用MinIO底层合并分片 + 合并后文件信息持久化到数据库，形成业务闭环
     * @param mergeChunkRequest 分片合并请求体，包含fileHash、chunkCount、fileType
     * @return 合并后完整文件的MinIO访问URL
     */
    @Override
    public String mergeChunk(MergeChunkRequest mergeChunkRequest) {
        String fileHash = mergeChunkRequest.getFileHash();
        int chunkCount = mergeChunkRequest.getChunkCount();
        String fileType = mergeChunkRequest.getFileType();

        // 合并文件
        String url = minioUtil.mergeChunk(fileHash, chunkCount, fileType);

        // 保存文件信息
        File file = new File();
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        file.setFileId(snowflake.nextId());
        file.setFileHash(fileHash);
        file.setFileUrl(url);
        boolean save = this.save(file);
        ThrowUtils.throwIf(!save, ErrorCode.PERSISTENCE_ERROR);

        return url;
    }
}




