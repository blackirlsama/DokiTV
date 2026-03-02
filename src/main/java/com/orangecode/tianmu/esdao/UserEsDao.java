package com.orangecode.tianmu.esdao;

import com.orangecode.tianmu.model.es.UserEs;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface UserEsDao extends ElasticsearchRepository<UserEs, Long> {

}