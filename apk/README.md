# apk/

공모전 GitHub 규격에 따른 release APK 위치다.

- 파일명: `ambitionandvision_TacticalEntryGuide_release.apk` (팀명 자리에는 GitHub 계정명을 쓴다)
- 생성: `bash scripts/build_release_apk.sh` (다른 이름을 쓰려면 `TEAM_NAME=이름` 을 앞에 붙인다)
- 이 release 빌드는 디버그 키로 서명돼 있어 설치·실행은 되지만 앱마켓 배포용 서명은 아니다.
- APK 가 아직 없다면 빌드 전이다. 존재하지 않는 파일을 있다고 가정하지 않는다.
