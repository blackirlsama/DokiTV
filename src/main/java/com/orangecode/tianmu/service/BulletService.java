package com.orangecode.tianmu.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.orangecode.tianmu.model.dto.bullet.DeleteBulletRequest;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.model.entity.Bullet;
import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;


public interface BulletService extends IService<Bullet> {



    void saveBulletToMySQL(SendBulletRequest SendBulletRequest);

    boolean deleteVideoBullet(DeleteBulletRequest deleteBulletRequest);

    List<OnlineBulletResponse> getBulletList(Long videoId);

    boolean bulletExists(Long bulletId);
}
