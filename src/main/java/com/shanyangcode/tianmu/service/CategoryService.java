package com.shanyangcode.tianmu.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.model.entity.Category;
import com.shanyangcode.tianmu.model.vo.category.CategoryListResponse;


public interface CategoryService extends IService<Category> {
    List<CategoryListResponse> categoryList();

}
