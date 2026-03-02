package com.orangecode.tianmu.model.dto.bullet;



import java.io.Serializable;

import lombok.Data;


/**
 * 删除弹幕 DTO
 */
@Data
public class DeleteBulletRequest implements Serializable {

    /**
     * 弹幕 id
     */

    private Long bulletId;


    /**
     *  uid
     */

    private Long userId;


    /**
     *  vid
     */

    private Long videoId;


    /**
     *  内容
     */

    private String content;



}
