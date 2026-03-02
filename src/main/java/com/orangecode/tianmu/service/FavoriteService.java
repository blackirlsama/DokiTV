package com.orangecode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.orangecode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.orangecode.tianmu.model.dto.video.VideoActionRequest;
import com.orangecode.tianmu.model.entity.Favorite;



public interface FavoriteService extends IService<Favorite> {

    Long favoriteVideo(VideoActionRequest videoActionRequest);


    Boolean cancelFavoriteVideo(CancelVideoActionRequest cancelVideoActionRequest);
}
