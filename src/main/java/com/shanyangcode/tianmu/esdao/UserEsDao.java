package com.shanyangcode.tianmu.esdao;

import com.shanyangcode.tianmu.model.es.UserEs;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface UserEsDao extends ElasticsearchRepository<UserEs, Long> {

}