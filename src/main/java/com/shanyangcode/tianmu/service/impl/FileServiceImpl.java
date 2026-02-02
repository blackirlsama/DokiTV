package com.shanyangcode.tianmu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.exception.BusinessException;
import com.shanyangcode.tianmu.mapper.FileMapper;
import com.shanyangcode.tianmu.entity.File;
import com.shanyangcode.tianmu.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements FileService {
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
}




