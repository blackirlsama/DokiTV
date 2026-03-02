package com.orangecode.tianmu.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.orangecode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.orangecode.tianmu.model.dto.video.CreateCommentRequest;
import com.orangecode.tianmu.model.entity.Comment;
import com.orangecode.tianmu.model.vo.video.CommentResponse;
import com.orangecode.tianmu.model.vo.video.CommentVideoResponse;


/**
* @author DP
* @description 针对表【comment(评论表)】的数据库操作Service
* @createDate 2025-05-07 10:32:46
*/
@SuppressWarnings({"all"})
public interface CommentService extends IService<Comment> {

    CommentResponse createCommentVideo(CreateCommentRequest createCommentRequest);

    Boolean deleteCommentVideo(CancelVideoActionRequest cancelVideoActionRequest);


    List<CommentVideoResponse> getCommentVideoList(Long videoId);
}
