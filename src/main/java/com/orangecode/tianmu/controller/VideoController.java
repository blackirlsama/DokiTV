package com.orangecode.tianmu.controller;

import java.util.List;

import com.orangecode.tianmu.common.BaseResponse;
import com.orangecode.tianmu.common.ResultUtils;
import com.orangecode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.orangecode.tianmu.model.dto.video.CreateCommentRequest;
import com.orangecode.tianmu.model.dto.video.VideoActionRequest;
import com.orangecode.tianmu.model.dto.video.VideoSubmitRequest;
import com.orangecode.tianmu.model.vo.video.*;
import com.orangecode.tianmu.service.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/video")
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
    public BaseResponse<List<VideoListResponse>> submitVideoList(@Valid @NotNull(message = "用户ID不能为空") @RequestParam Long userId) {
        return ResultUtils.success(videoService.getSubmitVideoList(userId));
    }

    @PostMapping("/like")
    public BaseResponse<Long> likeVideo(@RequestBody VideoActionRequest videoActionRequest) {
        return ResultUtils.success(likeService.likeVideo(videoActionRequest));
    }


    @PostMapping("/cancel/like")
    public BaseResponse<Boolean> cancelLikeVideo(@RequestBody CancelVideoActionRequest cancelVideoActionRequest) {
        return ResultUtils.success(likeService.cancelLikeVideo(cancelVideoActionRequest));
    }



    @PostMapping("/coin")
    public BaseResponse<Boolean> coinVideo( @RequestBody VideoActionRequest videoActionRequest) {
        return ResultUtils.success(coinService.coinVideo(videoActionRequest));
    }


    @PostMapping("/favorite")
    public BaseResponse<Long> favoriteVideo(@RequestBody VideoActionRequest videoActionRequest) {
        return ResultUtils.success(favoriteService.favoriteVideo(videoActionRequest));
    }

    @PostMapping("/cancel/favorite")
    public BaseResponse<Boolean> cancelFavoriteVideo(@RequestBody CancelVideoActionRequest cancelVideoActionRequest) {
        return ResultUtils.success(favoriteService.cancelFavoriteVideo(cancelVideoActionRequest));
    }

    @PostMapping("/create/comment")
    public BaseResponse<CommentResponse> createCommentVideo(@RequestBody CreateCommentRequest createCommentRequest) {
        return ResultUtils.success(commentService.createCommentVideo(createCommentRequest));
    }

    @PostMapping("/delete/comment")
    public BaseResponse<Boolean> deleteCommentVideo(@RequestBody CancelVideoActionRequest cancelVideoActionRequest) {
        return ResultUtils.success(commentService.deleteCommentVideo(cancelVideoActionRequest));
    }


    @GetMapping("/comment/list")
    public BaseResponse<List<CommentVideoResponse>> getCommentVideoList(@NotNull(message = "视频ID不能为空") @RequestParam Long videoId) {
        return ResultUtils.success(commentService.getCommentVideoList(videoId));
    }


    @PostMapping("/triple/action")
    public BaseResponse<TripleActionResponse> tripleAction(@RequestBody VideoActionRequest videoActionRequest) {
        return ResultUtils.success(videoService.tripleAction(videoActionRequest));
    }



    @GetMapping("/favorite/list")
    public BaseResponse<List<FavoriteVideoResponse>> favoriteVideoList(@Valid @NotNull(message = "用户ID不能为空") @RequestParam Long userId) {
        return ResultUtils.success(videoService.getFavoriteVideoList(userId));
    }

    @GetMapping("/like/list")
    public BaseResponse<List<VideoListResponse>> likeVideoList(@Valid @NotNull(message = "用户ID不能为空") @RequestParam Long userId) {
        return ResultUtils.success(videoService.getLikeVideoList(userId));
    }

    @GetMapping("/coin/list")
    public BaseResponse<List<VideoListResponse>> coinVideoList(@Valid @NotNull(message = "用户ID不能为空") @RequestParam Long userId) {
        return ResultUtils.success(videoService.getCoinVideoList(userId));
    }
}
