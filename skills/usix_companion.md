---
name: usix_companion
description: usix-companion 컴패니언 안드로이드 앱 화면 제어 메일 이메일 IMAP 받은메일함 본문 비즈메카 Thunderbird 썬더버드 알림 카톡 카카오톡 라인 메시지 답장 초안 스크롤
---

기존 도구로 usix-companion을 사용한다. USIX/usix-termux 소스와 승인 정책은 변경하지 않는다.

**메일 조회는 화면을 바꾸지 않는다.** `메일온거 확인해`, `메일 읽어줘`는 `python3 "$HOME/.usix/companion_mail.py" inbox '{"limit":20}'`로 목록을 읽고, 요청한 메일의 본문은 같은 목록의 `uid`·`uidvalidity`로 `read '{"uid":"반환된 uid","uidvalidity":"반환된 uidvalidity"}'`를 호출한다. 안 읽은 메일은 `inbox '{"limit":20,"unread":true}'`, 이전 페이지는 응답의 `next_before_uid`로 `inbox '{"limit":20,"before_uid":"반환된 next_before_uid"}'`를 사용한다. `has_more:true`이면 현재 목록은 전체가 아니다. 읽음 표시를 바꾸지 않는다. 첨부는 이름·종류만 확인된다.

`SetupRequired`·인증/연결 실패·`body_available:false`는 메일을 읽지 못한 상태다. `complete:false`와 `unavailable_uids`는 서버가 목록에만 반환하고 내용을 제공하지 않은 항목이므로 나머지 결과와 함께 미확인으로 알린다. 큰 첨부가 있는 메일도 본문만 따로 읽으며 첨부를 다운로드하지 않는다. 사용자에게 상태와 필요한 설정을 설명한다. `email-open`, `open`, 탭·뒤로·강제종료·다른 메일 앱 실행이나 패키지를 생략한 화면 조회로 자동 대체하지 않는다. 알림 0건과 화면 `[]`를 새 메일 없음으로 단정하지 않는다. 사용자에게 앱 열기나 화면 조작을 명시적으로 요청받았을 때만 아래 UI 흐름을 사용한다.

연결 설정은 사용자의 터미널에서 `python3 "$HOME/.usix/companion_mail.py" setup --email <계정주소> --host <IMAP서버>`로 한다. 비밀번호는 숨김 입력이며 `~/.usix/mail_account.json`(권한 600)에만 저장한다. 비밀번호·파일 내용을 모델·로그·명령 인자에 출력하지 않는다. `status`는 설정 존재 여부이고 실제 연결은 `check`로 확인한다. IMAP 조회에는 Companion 접근성이나 APK 실행이 필요 없다.

1. health: HTTP/Android 기능을 사용할 때 `shell`로 `python3 "$HOME/.usix/companion_http.py" health`를 호출한다. `paired:true`로 인증을 확인한다. 알림은 `listener:true`, UI 화면/메일 조작은 `accessibility:true`와 폰 잠금 해제가 필요하다. 토큰을 읽어서 출력하거나 모델 인자에 넣지 않는다. 페어링은 사용자의 터미널에서 `usix-termux pair`로 한다.
2. 알림: 등록된 `notif_list` 또는 `shell`로 `python3 "$HOME/.usix/companion_http.py" notifications`를 호출한다. 최신 `key`, `canReply`, 상대를 확인하고 요청 범위만 읽는다.
3. 화면/메일: 등록된 UI 도구를 사용한다. 없는 메일/스크롤 도구는 `shell`로 위 스크립트의 `email-open`, `screen '{"package":"net.thunderbird.android"}'`, `email-compose '{"to":"person@example.com","subject":"제목","body":"내용"}'`, `type '{"text":"내용","package":"net.thunderbird.android"}'`, `scroll '{"direction":"down","package":"net.thunderbird.android"}'`를 호출한다. 각 예시 앞에 `python3 "$HOME/.usix/companion_http.py"`를 붙인다. 실제 인자는 사용자의 요청에서 가져와 올바른 JSON과 셸 인자로 인코딩한다.
4. 화면을 먼저 읽고 앱·대상을 확인한다. 변화 뒤 다시 관찰하고 오래된 좌표나 모호한 대상을 탭하지 않는다. 메일 답장은 원본 스레드의 답장 화면에서 작성하고 계정·수신자·본문을 확인한다.
5. 발신은 사용자 권한과 기존 런타임 승인을 충족한 뒤 실행한다. 알림 답장은 등록된 `notif_reply` 또는 스크립트의 `reply '{"key":"최신 key","text":"승인된 내용"}'`를 사용한다. `email-compose`는 `sent:false`인 초안이고 `ok:true`는 전달 증거가 아니다. 실제 결과를 관찰하고 불명확하면 미확인으로 보고한다. 응답 유실 후 발신을 자동 반복하지 않는다.
6. 401은 페어링, 503은 접근성, 연결 실패는 Companion 앱을 확인한다. v2/WSS/OCR/스케줄러나 없는 `/email/send` 등을 가정하지 않는다. 비대화형 `usix-termux -c`는 변경 도구를 거부하며 이 스킬은 그 정책을 바꾸지 않는다.
