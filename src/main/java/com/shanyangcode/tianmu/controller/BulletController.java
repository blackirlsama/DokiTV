package com.shanyangcode.tianmu.controller;


import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.model.dto.bullet.DeleteBulletRequest;
import com.shanyangcode.tianmu.service.BulletService;

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
