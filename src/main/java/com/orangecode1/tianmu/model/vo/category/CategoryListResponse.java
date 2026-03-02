package com.orangecode.tianmu.model.vo.category;

import java.io.Serializable;

import lombok.Data;

@Data
public class CategoryListResponse implements Serializable {

    private int categoryId;

    private String categoryName;
}