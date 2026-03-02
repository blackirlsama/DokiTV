package com.orangecode.tianmu.controller;


import com.orangecode.tianmu.common.BaseResponse;
import com.orangecode.tianmu.common.ResultUtils;
import com.orangecode.tianmu.model.dto.bullet.DeleteBulletRequest;
import com.orangecode.tianmu.service.BulletService;

import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BulletController {


    @Resource
    private BulletService bulletService;

    @PostMapping("/video/delete/bullet")
    public BaseResponse<Boolean> deleteVideoBullet(@RequestBody DeleteBulletRequest deleteBulletRequest) {
        return ResultUtils.success(bulletService.deleteVideoBullet(deleteBulletRequest));
    }

}
