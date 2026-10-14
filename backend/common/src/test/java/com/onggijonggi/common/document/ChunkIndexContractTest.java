package com.onggijonggi.common.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ChunkIndexContractTest.java
 * Description : 매핑 리소스의 필드 목록이 적재·검색이 함께 쓰는 필드 상수와 같은지, 청크 ID 규칙이 청킹과 같은지 확인한다.
 */
class ChunkIndexContractTest {

	@Test
	void theMappingDeclaresExactlyTheContractFields() throws Exception {
		JsonNode mapping;
		try (InputStream in = new ClassPathResource(ChunkIndexContract.MAPPING).getInputStream()) {
			mapping = JsonMapper.builder().build().readTree(in);
		}
		List<String> declared = new ArrayList<>(mapping.path("mappings").path("properties").propertyNames());

		assertThat(declared).containsExactlyInAnyOrderElementsOf(ChunkIndexContract.FIELDS);
		assertThat(mapping.path("mappings").path("dynamic").asString()).isEqualTo("strict");
	}

	@Test
	void chunkIdsFromTheChunkerFollowTheContract() {
		UUID document = UUID.randomUUID();
		var chunks = new Chunker(new Chunker.Settings(800, 1200, 100)).chunk(document, 3, List.of(new Chunker.Section(null, "본문")));

		assertThat(chunks).singleElement().satisfies(chunk -> assertThat(chunk.id()).isEqualTo(ChunkIndexContract.chunkId(document, 3, 1)));
	}
}
