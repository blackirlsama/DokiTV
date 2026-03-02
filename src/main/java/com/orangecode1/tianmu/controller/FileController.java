package com.orangecode.tianmu.controller;

import java.util.List;
import java.util.Set;

import com.orangecode.tianmu.common.BaseResponse;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.common.ResultUtils;
import com.orangecode.tianmu.model.dto.file.InitUploadRequest;
import com.orangecode.tianmu.model.dto.file.MergeChunkRequest;
import com.orangecode.tianmu.service.FileService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 视频上传
 */
@RestController
@RequestMapping("/file")
@Slf4j
public class FileController {

    @Resource
    private FileService fileService;

    @GetMapping("/check")
    public BaseResponse<String> checkFileExistence(@RequestParam String fileHash) {
        String fileUrl = fileService.checkFileExistence(fileHash);
        if (fileUrl == null) {
            return ResultUtils.error(ErrorCode.VIDEO_NOT_FOUND_ERROR);
        }
        return ResultUtils.success(fileUrl);
    }

    @PostMapping("/get/upload/urls")
    public BaseResponse<List<String>> getUploadUrls(@RequestBody InitUploadRequest initUploadRequest) {
        return ResultUtils.success(fileService.getUploadUrls(initUploadRequest));
    }

    @GetMapping("/get/upload/progress")
    public BaseResponse<Set<Integer>> getUploadProgress(@RequestParam String fileHash) {
        return ResultUtils.success(fileService.getUploadProgress(fileHash));
    }

    @PostMapping("/merge/chunk")
    public BaseResponse<String> mergeChunk(@RequestBody MergeChunkRequest mergeChunkRequest) {
        return ResultUtils.success(fileService.mergeChunk(mergeChunkRequest));
    }
}
