package com.shanyangcode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.entity.File;

public interface FileService extends IService<File> {
    String checkFileExistence(String fileHash);
}
