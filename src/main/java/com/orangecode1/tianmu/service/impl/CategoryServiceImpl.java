package com.orangecode.tianmu.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.mapper.CategoryMapper;
import com.orangecode.tianmu.model.entity.Category;
import com.orangecode.tianmu.model.vo.category.CategoryListResponse;
import com.orangecode.tianmu.service.CategoryService;

import org.springframework.stereotype.Service;


@Service
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category>
    implements CategoryService {

    @Override
    public List<CategoryListResponse> categoryList() {
        List<Category> categoryList = this.list();

        return categoryList.stream().map(category -> {
            CategoryListResponse categoryListResponse = new CategoryListResponse();
            categoryListResponse.setCategoryId(category.getCategoryId());
            categoryListResponse.setCategoryName(category.getCategoryName());
            return categoryListResponse;
        }).collect(Collectors.toList());
    }


}




