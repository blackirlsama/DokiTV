package com.shanyangcode.tianmu.esdao;

import com.shanyangcode.tianmu.model.es.VideoEs;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface VideoEsDao extends ElasticsearchRepository<VideoEs, Long> {

}