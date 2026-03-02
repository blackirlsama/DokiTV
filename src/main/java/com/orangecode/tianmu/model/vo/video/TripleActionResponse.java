package com.orangecode.tianmu.model.vo.video;

import java.io.Serializable;

import lombok.Data;

@Data
public class TripleActionResponse implements Serializable {

    private Long LikeId;


    private boolean coin;


    private Long favoriteId;
}
