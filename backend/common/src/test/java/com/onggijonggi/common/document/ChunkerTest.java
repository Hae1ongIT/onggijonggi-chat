package com.onggijonggi.common.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Class Name : ChunkerTest.java
 * Description : 문단 우선 청킹의 경계·겹침·출처 위치·결정적 ID를 확인한다.
 */
class ChunkerTest {

	private final Chunker chunker = new Chunker(new Chunker.Settings(100, 150, 20));
	private final UUID document = UUID.randomUUID();

	@Test
	void packsParagraphsUpToTheTargetAndOverlapsTheNextChunk() {
		String a = "가".repeat(60), b = "나".repeat(60), c = "다".repeat(30);
		var chunks = chunker.chunk(document, 1, List.of(new Chunker.Section(null, a + "\n\n" + b + "\n\n" + c)));

		// 목표 100자: a(60) 다음에 b를 붙이면 넘친다 → a / (a 끝 20자 + b) / (b 끝 20자 + c).
		assertThat(chunks).hasSize(3);
		assertThat(chunks.get(0).content()).isEqualTo(a);
		assertThat(chunks.get(1).content()).isEqualTo("가".repeat(20) + "\n\n" + b);
		assertThat(chunks.get(2).content()).isEqualTo("나".repeat(20) + "\n\n" + c);
		assertThat(chunks).extracting(Chunker.Chunk::loc).containsExactly("para=1", "para=2", "para=3");
		assertThat(chunks).extracting(Chunker.Chunk::id).containsExactly(document + ":1:1", document + ":1:2", document + ":1:3");
	}

	@Test
	void splitsAnOverlongParagraphAtSentenceBoundariesWithoutTailOnlyChunks() {
		String sentence = "문장입니다 이것은 꽤 긴 문장입니다.";
		String paragraph = sentence.repeat(20);
		var chunks = chunker.chunk(document, 3, List.of(new Chunker.Section(null, paragraph)));

		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(150 + 20 + 2));
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isGreaterThan(20));
		assertThat(String.join("", chunks.stream().map(Chunker.Chunk::content).toList())).contains("문장입니다");
		assertThat(chunks.get(0).id()).isEqualTo(document + ":3:1");
	}

	@Test
	void textWithoutSentenceBoundariesIsCutAtTheMaximum() {
		String token = "가".repeat(400);
		var chunks = chunker.chunk(document, 1, List.of(new Chunker.Section(null, token)));

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(150 + 20 + 2));
		assertThat(chunks.get(0).content()).hasSize(150);
	}

	@Test
	void pdfChunksNeverCrossPagesAndCarryThePage() {
		var chunks = chunker.chunk(document, 1, List.of(new Chunker.Section(1, "첫 페이지"), new Chunker.Section(2, "둘째 페이지")));

		assertThat(chunks).extracting(Chunker.Chunk::loc).containsExactly("page=1", "page=2");
		assertThat(chunks).extracting(Chunker.Chunk::content).containsExactly("첫 페이지", "둘째 페이지");
	}

	@Test
	void sameInputGivesSameChunks() {
		var sections = List.of(new Chunker.Section(null, "하나\n\n둘\n\n" + "셋".repeat(200)));
		assertThat(chunker.chunk(document, 1, sections)).isEqualTo(chunker.chunk(document, 1, sections));
	}

	/** 빈 줄 없는 큰 파일(10MB CSV·로그)은 문단 하나다. 나누는 비용이 길이에 비례해야 한다(전에는 남은 문자열을 매번 복사했다). */
	@Test
	void aHugeSingleParagraphIsSplitInLinearTime() {
		var production = new Chunker(new Chunker.Settings(800, 1200, 100));
		StringBuilder text = new StringBuilder();
		for (int line = 0; text.length() < 4_000_000; line++) text.append("행").append(line).append(",값,값,값,값,값,값,값,값,값,값,값,값,값,값,값,값,값,값,값,값\n");
		String paragraph = text.toString().strip();

		var chunks = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
				() -> production.chunk(document, 1, List.of(new Chunker.Section(null, paragraph))));

		assertThat(chunks.size()).isGreaterThan(3000);
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(1200 + 100 + 2));
		assertThat(chunks.get(0).content()).startsWith("행0,");
		assertThat(chunks.get(chunks.size() - 1).content()).endsWith(paragraph.substring(paragraph.length() - 20));
	}
}
