package com.shanyangcode.tianmu.controller;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;

import java.util.List;

import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.model.dto.bullet.DeleteBulletRequest;
import com.shanyangcode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.CreateCommentRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoSubmitRequest;
import com.shanyangcode.tianmu.model.vo.video.*;
import com.shanyangcode.tianmu.service.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 视频投稿
 * 该Controller类用于处理与视频相关的HTTP请求
 */
@RestController // 标记该类为RESTful控制器，所有方法默认返回JSON格式数据
@Slf4j // Lombok提供的日志注解，自动生成log对象
public class VideoController {

    @Resource // Spring提供的注解，用于自动注入依赖
    private BulletService bulletService; // 弹幕服务接口，用于处理弹幕相关的业务逻辑

    /**
     * @MethodName deletVideoBullet
     * @Description 删除视频弹幕
     * @param: deleteBulletRequest 删除弹幕请求对象，包含需要删除的弹幕信息
     * @return: Boolean
     */
    @PostMapping("/video/delete/bullet")
    public BaseResponse<Boolean> deleteVideoBullet(@Valid @RequestBody DeleteBulletRequest deleteBulletRequest) {
        return ResultUtils.success(bulletService.deleteVideoBullet(deleteBulletRequest));
    }
}