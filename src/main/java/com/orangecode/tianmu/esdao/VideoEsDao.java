package com.orangecode.tianmu.esdao;

import com.orangecode.tianmu.model.es.VideoEs;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface VideoEsDao extends ElasticsearchRepository<VideoEs, Long> {

}