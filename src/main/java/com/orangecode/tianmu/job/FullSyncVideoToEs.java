package com.orangecode.tianmu.job;

import java.util.List;
import java.util.stream.Collectors;

import com.orangecode.tianmu.esdao.VideoEsDao;
import com.orangecode.tianmu.model.entity.Video;
import com.orangecode.tianmu.model.es.VideoEs;
import com.orangecode.tianmu.service.VideoService;

import cn.hutool.core.collection.CollUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class FullSyncVideoToEs implements CommandLineRunner {

    @Resource
    private VideoService videoService;

    @Resource
    private VideoEsDao videoEsDao;

    @Override
    public void run(String... args) {
        // 全量获取视频（数据量不大的情况下使用）
        List<Video> videoList = videoService.list();
        if (CollUtil.isEmpty(videoList)) {
            return;
        }
        // 转为 ES 实体类
        List<VideoEs> videoEsList = videoList.stream().map(video -> {
            VideoEs videoEs = new VideoEs();
            videoEs.setId(video.getVideoId());
            videoEs.setTitle(video.getTitle());
            return videoEs;
        }).collect(Collectors.toList());

        System.out.println(videoEsList);
        // 分页批量插入到 ES
        final int pageSize = 500;
        int total = videoList.size();
        log.info("FullSyncVideoToEs start, total {}", total);
        for (int i = 0; i < total; i += pageSize) {
            // 注意同步的数据下标不能超过总数据量
            int end = Math.min(i + pageSize, total);
            log.info("sync from {} to {}", i, end);
            videoEsDao.saveAll(videoEsList.subList(i, end));
        }
        log.info("FullSyncVideoToEs end, total {}", total);
    }
}