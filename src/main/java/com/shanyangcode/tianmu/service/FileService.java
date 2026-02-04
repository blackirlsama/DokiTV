package com.shanyangcode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.model.dto.file.InitUploadRequest;
import com.shanyangcode.tianmu.model.dto.file.MergeChunkRequest;
import com.shanyangcode.tianmu.model.entity.File;

import java.util.List;
import java.util.Set;

public interface FileService extends IService<File> {
    String checkFileExistence(String fileHash);

    List<String> getUploadUrls(InitUploadRequest initUploadRequest);

    Set<Integer> getUploadProgress(String fileHash);

    String mergeChunk(MergeChunkRequest mergeChunkRequest);
}
