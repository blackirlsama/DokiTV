package com.orangecode.tianmu.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.orangecode.tianmu.model.entity.Category;
import com.orangecode.tianmu.model.vo.category.CategoryListResponse;


public interface CategoryService extends IService<Category> {
    List<CategoryListResponse> categoryList();

}
