package com.onggijonggi.etl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Class Name : EtlApplication.java
 * Description : 방 문서 인제스천 ETL 워커(#340) 진입점. 웹 서버 없이 PostgreSQL의 처리 회차를 가져가 처리한다.
 *               EnableScheduling은 지난 회차 청크 정리(RunSweeper)에 필요하다. 처리 자체는 IngestionWorkers가 돈다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class EtlApplication {

	/** main: 스프링 부트 애플리케이션을 구동한다. */
	public static void main(String[] args) {
		SpringApplication.run(EtlApplication.class, args);
	}
}
