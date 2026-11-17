# 작업 규칙 (Claude용)
- 사용자는 비개발자. 수정 요청 시 Claude가 코드를 고쳐 main에 바로 push → GitHub Actions가 APK 빌드 → Releases 게시 → 사용자는 Obtainium으로 업데이트.
- push 후 반드시 Actions 결과를 확인하고, 빌드 실패 시 로그 보고 고쳐서 다시 push.
- 서명 키(app/silentcam.keystore)는 절대 바꾸지 말 것(바꾸면 업데이트 설치가 안 됨).
- 인터넷 권한 추가 금지. 셔터 소리 내지 말 것(무음 유지).
- 범위촬영 범위는 항상 축에 나란한 직사각형만 허용.
- 사용자 안내는 한국어 개조식으로.
