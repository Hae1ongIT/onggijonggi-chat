package com.onggijonggi.common.document;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : ChunkIndexContract.java
 * Description : 방 문서 청크 검색 인덱스의 계약 — 매핑 리소스(es-thr-doc-chunk-index.json)의 필드 이름, 별칭, 청크 ID 규칙. 적재(ETL)와
 *               검색(BFF)이 같은 이름을 쓰게 한 곳에 둔다. 한쪽만 이름을 바꾸면 컴파일·테스트를 통과한 채 운영 검색이 0건이 된다.
 *               필드를 바꾸면 매핑 JSON과 이 상수를 함께 바꾸고 인덱스 이름(버전)을 올린다(ChunkIndexContractTest가 둘을 대조한다).
 */
public final class ChunkIndexContract {

	/** 쓰기·검색은 항상 이 별칭으로 한다. 인덱스 이름(버전)은 ETL 설정이 정한다. */
	public static final String ALIAS = "thr_doc_chunk";
	/** 매핑 리소스(공용 모듈의 classpath). */
	public static final String MAPPING = "es-thr-doc-chunk-index.json";

	public static final String CHUNK_ID = "chunk_id";
	public static final String DOC_ID = "doc_id";
	public static final String THR_ID = "thr_id";
	public static final String TNN_ID = "tnn_id";
	public static final String RUN_SEQ = "run_seq";
	public static final String SEQ = "seq";
	public static final String CONTENT = "content";
	public static final String LOC = "loc";
	public static final String EMB = "emb";
	public static final String EMB_MDL = "emb_mdl";

	/** 매핑에 있어야 하는 필드 전체. */
	public static final List<String> FIELDS = List.of(CHUNK_ID, DOC_ID, THR_ID, TNN_ID, RUN_SEQ, SEQ, CONTENT, LOC, EMB, EMB_MDL);

	private ChunkIndexContract() {
	}

	/** 청크 ID(색인 문서 _id와 같다). 문서·회차·순번으로 정해져 같은 회차를 다시 적재하면 덮어쓴다. */
	public static String chunkId(UUID document, int runSeq, int seq) {
		return document + ":" + runSeq + ":" + seq;
	}
}
