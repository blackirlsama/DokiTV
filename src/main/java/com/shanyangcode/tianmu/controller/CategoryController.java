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

@RestController
public class CategoryController {

    @Resource
    private VideoService videoService;

    @Resource
    private CategoryService categoryService;

    @GetMapping("/category/list")
    public BaseResponse<List<VideoListResponse>> categoryList(@RequestParam Integer categoryId) {
        return ResultUtils.success(videoService.getCategoryVideoList(categoryId));
    }

    @GetMapping("/category")
    public BaseResponse<List<CategoryListResponse>> category() {
        return ResultUtils.success(categoryService.categoryList());
    }
}
