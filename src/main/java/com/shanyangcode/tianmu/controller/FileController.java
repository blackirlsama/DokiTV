package com.shanyangcode.tianmu.controller;

import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.model.dto.file.InitUploadRequest;
import com.shanyangcode.tianmu.model.dto.file.MergeChunkRequest;
import com.shanyangcode.tianmu.service.FileService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 视频上传
 */
@RestController
@RequestMapping("/api/file")
@Slf4j
public class FileController {

    /**
     * 注入文件业务处理服务层接口
     */
    @Resource
    private FileService fileService;

    /**
     * 文件存在性校验接口
     * 根据文件唯一哈希值，检查文件是否已上传至服务器/MinIO
     *
     * @param fileHash 文件唯一哈希值（前端计算后传入，用于文件去重）
     * @return BaseResponse<String> 校验结果：存在则返回文件访问URL，不存在则返回自定义异常
     */
    @GetMapping("/check")
    public BaseResponse<String> checkFileExistence(@RequestParam String fileHash) {
        String fileUrl = fileService.checkFileExistence(fileHash);
        if (fileUrl == null) {
            return ResultUtils.error(ErrorCode.VIDEO_NOT_FOUND_ERROR);
        }
        return ResultUtils.success(fileUrl);
    }


    /**
     * 分片上传初始化接口
     * 获取所有文件分片的MinIO临时上传URL（前端根据URL直传分片）
     *
     * @param initUploadRequest 分片上传初始化请求体，包含文件哈希、分片总数（@Valid开启参数校验）
     * @return BaseResponse<List<String>> 分片上传URL列表，按分片索引顺序排列，一一对应
     */
    @PostMapping("/get/upload/urls")
    public BaseResponse<List<String>> getUploadUrls(@Valid @RequestBody InitUploadRequest initUploadRequest) {
        return ResultUtils.success(fileService.getUploadUrls(initUploadRequest));
    }


    /**
     * 分片上传进度查询接口
     * 供前端轮询调用，查询指定文件的已上传分片索引，判断是否满足合并条件
     *
     * @param fileHash 必传，文件唯一哈希值（@Valid开启参数校验，保证非空）
     * @return BaseResponse<Set<Integer>> 已上传分片的索引集合（Set保证索引唯一，无重复），未上传则返回空集合
     */
    @GetMapping("/get/upload/progress")
    public BaseResponse<Set<Integer>> getUploadProgress(@Valid @RequestParam String fileHash) {
        return ResultUtils.success(fileService.getUploadProgress(fileHash));
    }


    /**
     * 分片合并接口
     * 分片上传的最终步骤，仅当全部分片上传完成后调用才会执行成功
     * 服务层调用MinIO接口将多个分片合并为一个完整文件，并生成永久访问URL
     *
     * @param mergeChunkRequest 必传，分片合并请求体（@Valid开启JSR380参数校验，保证核心参数合法）
     * @return BaseResponse<String> 合并成功后返回文件的永久访问URL，可直接用于文件查看/播放/下载
     */
    @PostMapping("/merge/chunk")
    public BaseResponse<String> mergeChunk(@Valid @RequestBody MergeChunkRequest mergeChunkRequest) {
        return ResultUtils.success(fileService.mergeChunk(mergeChunkRequest));
    }

}
