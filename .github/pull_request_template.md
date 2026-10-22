## 무엇을

관련 이슈: Closes #
<!-- 이슈를 닫는 PR이 아니라면 `Refs #`로 바꿔 주세요 -->

## 왜

## 확인 방법

- [ ] `gradlew build` 통과 (백엔드를 고쳤다면 — 테스트가 함께 돈다)
- [ ] `bunx tsc --noEmit`·`bun run test`·`bun lint:check` 통과 (프론트를 고쳤다면)
- [ ] `uv run --frozen pytest -q` 통과 (문서 워커를 고쳤다면)
- [ ] 동작이 바뀌었다면 테스트를 붙였다 (어렵다면 그 이유를 위에 적는다)
- [ ] 관련 문서를 함께 고쳤다
