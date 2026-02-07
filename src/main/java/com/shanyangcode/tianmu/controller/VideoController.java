package com.shanyangcode.tianmu.controller;

import java.util.List;

import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.CreateCommentRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoSubmitRequest;
import com.shanyangcode.tianmu.model.vo.video.*;
import com.shanyangcode.tianmu.service.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    @Resource
    private VideoService videoService;

    @Resource
    private LikeService likeService;

    @Resource
    private CoinService coinService;

    @Resource
    private FavoriteService favoriteService;

    @Resource
    private CommentService commentService;

    @PostMapping("/submit")
    public BaseResponse<Boolean> submit(@RequestParam String fileUrl, @RequestParam Long userId, @RequestParam MultipartFile file, @RequestParam String title, @RequestParam Integer type, @RequestParam Double duration, @RequestParam Integer categoryId, @RequestParam String tags, @RequestParam String description) throws Exception {
        VideoSubmitRequest videoSubmitRequest = new VideoSubmitRequest(fileUrl, userId, file, title, type, duration, categoryId, tags, description);
        return ResultUtils.success(videoService.submit(videoSubmitRequest));
    }


    @GetMapping("/list")
    public BaseResponse<List<VideoListResponse>> videoList(@RequestParam Integer current, @RequestParam Integer pageSize) {
        return ResultUtils.success(videoService.getVideoList(current, pageSize));
    }


    @PostMapping("/detail")
    public BaseResponse<VideoResponse> videoDetail(@RequestBody VideoActionRequest videoActionRequest) {
        return ResultUtils.success(videoService.videoDetail(videoActionRequest));
    }


    @GetMapping("/submit/list")
    public BaseResponse<List<VideoListResponse>> submitVideoList(@Valid @NotEmpty(message = "用户ID不能为空") @RequestParam Long userId) {
        return ResultUtils.success(videoService.getSubmitVideoList(userId));
    }

    @PostMapping("/like")
    public BaseResponse<Long> likeVideo(@Valid @RequestBody VideoActionRequest videoActionRequest) {
        return ResultUtils.success(likeService.likeVideo(videoActionRequest));
    }


    @PostMapping("/cancel/like")
    public BaseResponse<Boolean> cancelLikeVideo(@Valid @RequestBody CancelVideoActionRequest cancelVideoActionRequest) {
        return ResultUtils.success(likeService.cancelLikeVideo(cancelVideoActionRequest));
    }



    @PostMapping("/coin")
    public BaseResponse<Boolean> coinVideo(@Valid @RequestBody VideoActionRequest videoActionRequest) {
        return ResultUtils.success(coinService.coinVideo(videoActionRequest));
    }


    @PostMapping("/favorite")
    public BaseResponse<Long> favoriteVideo(@Valid @RequestBody VideoActionRequest videoActionRequest) {
        return ResultUtils.success(favoriteService.favoriteVideo(videoActionRequest));
    }

    @PostMapping("/video/cancel/favorite")
    public BaseResponse<Boolean> cancelFavoriteVideo(@Valid @RequestBody CancelVideoActionRequest cancelVideoActionRequest) {
        return ResultUtils.success(favoriteService.cancelFavoriteVideo(cancelVideoActionRequest));
    }

    @PostMapping("/create/comment")
    public BaseResponse<CommentResponse> createCommentVideo(@Valid @RequestBody CreateCommentRequest createCommentRequest) {
        return ResultUtils.success(commentService.createCommentVideo(createCommentRequest));
    }

    @PostMapping("/delete/comment")
    public BaseResponse<Boolean> deleteCommentVideo(@Valid @RequestBody CancelVideoActionRequest cancelVideoActionRequest) {
        return ResultUtils.success(commentService.deleteCommentVideo(cancelVideoActionRequest));
    }


    @GetMapping("/comment/list")
    public BaseResponse<List<CommentVideoResponse>> getCommentVideoList(@Valid @NotEmpty(message = "视频ID不能为空") @RequestParam Long videoId) {
        return ResultUtils.success(commentService.getCommentVideoList(videoId));
    }


    @PostMapping("/triple/action")
    public BaseResponse<TripleActionResponse> tripleAction(@Valid @RequestBody VideoActionRequest videoActionRequest) {
        return ResultUtils.success(videoService.tripleAction(videoActionRequest));
    }
}
