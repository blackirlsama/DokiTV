package com.shanyangcode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.entity.Coin;



public interface CoinService extends IService<Coin> {


    Boolean coinVideo(VideoActionRequest videoActionRequest);
}
