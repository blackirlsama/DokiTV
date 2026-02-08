package com.shanyangcode.tianmu.controller;

import java.util.List;

import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.model.vo.category.CategoryListResponse;
import com.shanyangcode.tianmu.model.vo.video.VideoListResponse;
import com.shanyangcode.tianmu.service.CategoryService;
import com.shanyangcode.tianmu.service.VideoService;

import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分类控制器
 * 处理与分类相关的HTTP请求，包括获取分类列表和分类下的视频列表
 */
@RestController
public class CategoryController {

    @Resource
    private VideoService videoService;  // 视频服务，用于处理视频相关的业务逻辑

    @Resource
    private CategoryService categoryService;  // 分类服务，用于处理分类相关的业务逻辑

    /**
     * 获取指定分类下的视频列表
     * @param categoryId 分类ID
     * @return 返回视频列表的响应结果
     */
    @GetMapping("/category/list")
    public BaseResponse<List<VideoListResponse>> categoryList(@RequestParam Integer categoryId) {
        return ResultUtils.success(videoService.getCategoryVideoList(categoryId));
    }

    /**
     * 获取所有分类列表
     * @return 返回分类列表的响应结果
     */
    @GetMapping("/category")
    public BaseResponse<List<CategoryListResponse>> category() {
        return ResultUtils.success(categoryService.categoryList());
    }
}
