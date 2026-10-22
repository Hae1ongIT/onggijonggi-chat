# bff — Spring Boot WebFlux BFF

02·03·04 계층. 프론트(`frontend/`)의 **단일 진입점**.
- 02 EDGE: Spring Security 필터체인 + Keycloak(OIDC/RBAC) · 분당 요청 제한
- 03 CORE: Spring AI `ChatClient` 스트리밍(WebFlux, `Flux<String>` — 프레이밍 없는 `text/plain`)
- 04 DATA: PostgreSQL(JPA/Hibernate + Flyway)

그 밖에 협업방 WebSocket(`chat/`), 팀·직급 권한 판정(Casbin 연동, `authz/`), 방 문서 검색(Elasticsearch 키워드·벡터 하이브리드, `rag/`)을 맡는다.
AI 답변은 도구로 방 문서 검색·웹 검색(`web/`)·현재 시각·새 문서 만들기·docx 개정본을 부르고, 문서 생성·원본 보관은 문서 워커(`worker/`)에 맡긴다.
방 문서의 추출·청킹·임베딩·색인은 별도 모듈 `backend/etl`이 한다.
