package com.shanyangcode.tianmu.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.mapper.CategoryMapper;
import com.shanyangcode.tianmu.model.entity.Category;
import com.shanyangcode.tianmu.model.vo.category.CategoryListResponse;
import com.shanyangcode.tianmu.service.CategoryService;

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




