package com.shanyangcode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.model.dto.bullet.DeleteBulletRequest;
import com.shanyangcode.tianmu.model.dto.bullet.SendBulletRequest;
import com.shanyangcode.tianmu.model.entity.Bullet;


public interface BulletService extends IService<Bullet> {



    void saveBulletToMySQL(SendBulletRequest SendBulletRequest);

    boolean deleteVideoBullet(DeleteBulletRequest deleteBulletRequest);


}
