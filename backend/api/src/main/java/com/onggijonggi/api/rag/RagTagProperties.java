package com.onggijonggi.api.rag;

import com.onggijonggi.common.document.TagIndexContract;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Class Name : RagTagProperties.java
 * Description : 검색의 태그 채널 설정(app.rag.tag.*, #362). 태그(카테고리·핵심 키워드·요약)는 문서 단위 신호라, 태그 채널은 관련 문서를
 *               찾고 그 문서 안에서 질문과 의미가 가장 가까운 조각을 대표로 세워 다른 채널과 순위로 합친다. 필터가 아니라 순위만 더한다.
 *               검색은 채팅 답변에 바로 쓰이므로 기본은 꺼 두고, 평가 세트(rag-eval)로 무관 질문 오채택이 늘지 않음을 확인한 뒤 켠다.
 *               minimumShouldMatch는 질문 낱말 중 키워드·요약(필드마다)에 맞아야 하는 비율이다. 기본 "3<75%"는 낱말 3개 이하면 전부,
 *               넘으면 75% — 비율만 두면 짧은 질문이 낱말 1개 일치로 통과해 무관 질문이 근거를 얻었다(평가에서 40%는 오채택 4/10,
 *               이 값은 끔과 같은 1/10. 키워드·요약을 합쳐 세는 cross_fields도 나아지지 않았다). chunkMinSimilarity는 태그로만 찾은 문서의 대표 조각이
 *               넘어야 하는 코사인 유사도다(못 넘으면 엉뚱한 조각이 근거로 들어가지 않게 뺀다).
 */
@ConfigurationProperties(prefix = "app.rag.tag")
public record RagTagProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue(TagIndexContract.ALIAS) String alias,
		@DefaultValue("3<75%") String minimumShouldMatch,
		@DefaultValue("10") int candidates,
		@DefaultValue("0.4") double chunkMinSimilarity) {

	/** 태그 채널을 쓰지 않는 설정(테스트·기존 생성자용). */
	public static RagTagProperties disabled() {
		return new RagTagProperties(false, TagIndexContract.ALIAS, "3<75%", 10, 0.4);
	}
}
