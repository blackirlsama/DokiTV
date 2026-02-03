package com.shanyangcode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.entity.File;
import com.shanyangcode.tianmu.model.dto.file.InitUploadRequest;

import java.util.List;

public interface FileService extends IService<File> {

    String checkFileExistence(String fileHash);

    List<String> getUploadUrls(InitUploadRequest initUploadRequest);

}
