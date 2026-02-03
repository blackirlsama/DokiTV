package com.shanyangcode.tianmu.controller;

import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.model.dto.file.InitUploadRequest;
import com.shanyangcode.tianmu.service.FileService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

}
