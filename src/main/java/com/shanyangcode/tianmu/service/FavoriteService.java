package com.shanyangcode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.entity.Favorite;



public interface FavoriteService extends IService<Favorite> {

    Long favoriteVideo(VideoActionRequest videoActionRequest);


    Boolean cancelFavoriteVideo(CancelVideoActionRequest cancelVideoActionRequest);
}
