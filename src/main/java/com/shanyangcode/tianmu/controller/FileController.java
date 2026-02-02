package com.shanyangcode.tianmu.controller;

import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.service.FileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
