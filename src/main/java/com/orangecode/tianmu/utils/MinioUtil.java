package com.orangecode.tianmu.utils;

import jakarta.annotation.Resource;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.ThrowUtils;

import cn.hutool.core.util.StrUtil;
import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class MinioUtil {

    @Resource
    private MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${minio.url}")
    private String url;


    /**
     * @MethodName ensureBucketExists
     * @Description 确保桶存在
     * @param: bucketName
     * @return: boolean
     * @Date 2025/4/10 15:30
     */
    public boolean ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            return true; // 无论是否新建，只要最终桶存在即返回true
        } catch (Exception e) {
            throw new RuntimeException("Bucket操作失败", e);
        }
    }


    /**
     * @MethodName uploadChunkUrl
     * @Description 获取分片上传临时链接
     * @param: fileHash
     * @param: countIndex
     * @param: expires
     * @param: timeUnit
     * @param: fileType
     * @return: String
     * @Date 2025/4/10 15:30
     */
    public String uploadChunkUrl(String fileHash, int countIndex, Integer expires, TimeUnit timeUnit) {
        String objectName = String.format("%s/%s", fileHash, countIndex);
        ensureBucketExists();

        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT).bucket(bucketName).object(objectName).expiry(expires, timeUnit).build());
        } catch (Exception e) {
            throw new RuntimeException("生成临时链接失败", e);

        }
    }


    /**
     * @MethodName downloadUrl
     * @Description 获取下载链接
     * @param: fileName
     * @return: String
     * @Date 2025/4/10 15:31
     */
    public String downloadUrl(String fileName) {
        return url + StrUtil.SLASH + bucketName + StrUtil.SLASH + fileName;
    }


    /**
     * @MethodName mergeChuck
     * @Description 合并分片
     * @param: fileHash
     * @param: chunkCount
     * @param: fileType
     * @return: String
     * @Date 2025/4/10 15:32
     */
    public String mergeChunk(String fileHash, int chunkCount, String fileType) {
        ensureBucketExists();
        String ObjectName = fileHash;
        int count = chunkCount;
        int chunkLists = getChunkProgress(fileHash).size();
        ThrowUtils.throwIf(count != chunkLists, ErrorCode.MERGE_FILE_ERROR);
        List<ComposeSource> composeSources = new ArrayList<>();
        List<String> objectNames = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String objectName = String.format("%s/%s", ObjectName, i);
            objectNames.add(objectName);
            composeSources.add(ComposeSource.builder().bucket(bucketName).object(objectName).build());
        }
        ThrowUtils.throwIf(count != composeSources.size(), ErrorCode.CHUNK_FILE_LACK);
        String finalObjectName = fileHash + "." + fileType;
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", new ContentTypeUtil().getType(fileType));
        headers.put("Content-Disposition", "inline");
        ComposeObjectArgs composeObjectArgs = ComposeObjectArgs.builder().bucket(bucketName).object(finalObjectName).sources(composeSources).headers(headers).build();
        // 合并文件分片
        try {
            minioClient.composeObject(composeObjectArgs);
            for (String objectName : objectNames) {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(objectName).build());
            }
        } catch (Exception e) {
            throw new RuntimeException("合并文件分片失败", e);
        }
        return downloadUrl(finalObjectName);
    }


    /**
     * @MethodName updateCover
     * @Description 上传封面
     * @param: file
     * @return: String
     * @Date 2025/4/10 15:32
     */
    public String updateCover(MultipartFile file) throws Exception {
        ensureBucketExists();
        String fileSuffix = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".") + 1);
        String id = UUID.randomUUID().toString();
        String fileName = id + "." + fileSuffix;
        InputStream inputStream = file.getInputStream();
        String contentType = new ContentTypeUtil().getType(fileSuffix);
        try {
            minioClient.putObject(PutObjectArgs.builder().bucket(bucketName).object(fileName).stream(inputStream, file.getSize(), -1).contentType(contentType).build());
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败", e);
        }
        return downloadUrl(fileName);
    }


    /**
     * @MethodName getChunkProgress
     * @Description 获取分片上传进度
     * @param: fileHash
     * @return: Set<Integer>
     * @Date 2025/4/10 15:33
     */
    public Set<Integer> getChunkProgress(String fileHash) {
        ensureBucketExists();
        Set<Integer> chunks = new HashSet<>();
        String prefix = fileHash + "/";
        try {
            // 创建分页迭代器
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder().bucket(bucketName).prefix(prefix).recursive(false) // 不递归子目录
                    .build());

            // 遍历结果并过滤有效分片
            for (Result<Item> result : results) {
                Item item = result.get();
                if (!item.isDir()) { // 排除目录对象
                    String objectName = item.objectName();
                    String chunkIndex = objectName.substring(objectName.lastIndexOf("/") + 1);
                    chunks.add(Integer.valueOf(chunkIndex));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("获取分片列表失败: " + e.getMessage(), e);
        }
        return chunks;
    }
}
