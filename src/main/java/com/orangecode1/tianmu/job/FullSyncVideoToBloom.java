package com.orangecode.tianmu.job;

import java.util.List;

import com.orangecode.tianmu.model.entity.Video;
import com.orangecode.tianmu.service.VideoService;
import com.orangecode.tianmu.utils.BitMapBloomUtil;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class FullSyncVideoToBloom implements CommandLineRunner {


    @Resource
    private VideoService videoService;

    @Override
    public void run(String... args) {
        // 全量获取题目（数据量不大的情况下使用）
        List<Video> videoList = videoService.list();

        log.info("FullSyncVideoToBloom start");
        for (Video video : videoList) {
            BitMapBloomUtil.add(video.getVideoId().toString());
            log.info("videoId {}", video.getVideoId());

        }
        log.info("FullSyncQuestionToEs end");
    }
}