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
    public BaseResponse<List<String>> getUploadUrls(@Valid @RequestBody InitUploadRequest initUploadRequest) {
        return ResultUtils.success(fileService.getUploadUrls(initUploadRequest));
    }

    @GetMapping("/get/upload/progress")
    public BaseResponse<Set<Integer>> getUploadProgress(@Valid @RequestParam String fileHash) {
        return ResultUtils.success(fileService.getUploadProgress(fileHash));
    }

    @PostMapping("/merge/chunk")
    public BaseResponse<String> mergeChunk(@Valid @RequestBody MergeChunkRequest mergeChunkRequest) {
        return ResultUtils.success(fileService.mergeChunk(mergeChunkRequest));
    }
}
