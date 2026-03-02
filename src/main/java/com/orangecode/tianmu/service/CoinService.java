package com.orangecode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.orangecode.tianmu.model.dto.video.VideoActionRequest;
import com.orangecode.tianmu.model.entity.Coin;



public interface CoinService extends IService<Coin> {


    Boolean coinVideo(VideoActionRequest videoActionRequest);
}
