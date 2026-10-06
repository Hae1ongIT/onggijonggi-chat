package com.onggijonggi.api.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Class Name : RagConfiguration.java
 * Description : 방 문서 검색 설정(app.rag.*)을 레코드로 바인딩한다.
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
class RagConfiguration {
}
