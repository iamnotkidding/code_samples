# AutoTouch - Android UI Automation App

스크립트 기반 Android UI 자동화 앱. TXT 파일로 작성한 명령어를 파싱하여
다양한 앱의 메뉴 클릭, 스크롤, 화면 전환을 자동 수행합니다.

---

## 주요 기능

| 기능 | 설명 |
|------|------|
| **UI Automation** | AccessibilityService를 이용한 좌표/텍스트/ID 기반 클릭, 스크롤, 스와이프, 텍스트 입력 |
| **TXT 스크립트 파싱** | 직관적인 텍스트 명령어를 파싱하여 순차 실행 |
| **백그라운드 실행** | Foreground Service로 앱이 백그라운드에 있어도 스크립트 계속 실행 |
| **알림창 제어** | 알림에서 실행 현황 확인, 일시정지/재개/중지 버튼 제공 |
| **중지 시 복귀** | 중지하면 자동으로 MainActivity로 복귀 |

---

## 프로젝트 구조

```
AutoTouchApp/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/autotouch/app/
│   │   ├── parser/
│   │   │   ├── ScriptCommand.kt      # 명령어 sealed class (20+ 종류)
│   │   │   └── ScriptParser.kt       # TXT → ScriptCommand 파서
│   │   ├── service/
│   │   │   ├── AutoTouchAccessibilityService.kt  # UI 자동화 엔진
│   │   │   └── ScriptExecutorService.kt          # 포그라운드 서비스
│   │   ├── receiver/
│   │   │   └── NotificationActionReceiver.kt     # 알림 버튼 처리
│   │   └── ui/
│   │       └── MainActivity.kt        # 메인 화면
│   └── res/
│       ├── layout/activity_main.xml   # 다크 테마 UI
│       ├── drawable/                  # 버튼, 카드, 아이콘
│       ├── values/                    # 색상, 문자열, 스타일
│       └── xml/accessibility_service_config.xml
├── build.gradle.kts
├── settings.gradle.kts
└── sample_script.txt                  # 샘플 스크립트
```

---

## 아키텍처

```
┌─────────────────┐     파싱      ┌──────────────┐
│  TXT Script     │ ──────────▶  │ ScriptParser  │
│  (사용자 작성)    │              │              │
└─────────────────┘              └──────┬───────┘
                                        │ List<ScriptCommand>
                                        ▼
┌─────────────────┐    명령 실행   ┌──────────────────────────┐
│ ScriptExecutor  │ ──────────▶  │ AutoTouchAccessibility   │
│ Service         │              │ Service                  │
│ (Foreground)    │              │ (제스처/노드 제어)          │
└────────┬────────┘              └──────────────────────────┘
         │
         │ 알림 업데이트
         ▼
┌─────────────────┐   버튼 액션   ┌──────────────────────────┐
│ Notification    │ ◀──────────  │ NotificationAction       │
│ (현황/버튼)      │              │ Receiver                 │
└─────────────────┘              └──────────────────────────┘
```

---

## 스크립트 문법

### 클릭
```
CLICK 540 960                  # 좌표 (x, y) 클릭
CLICK_TEXT "확인"               # 텍스트로 요소 찾아 클릭
CLICK_TEXT "항목" 2             # 동일 텍스트 중 3번째 (0-based index)
CLICK_ID "com.app:id/button"   # Resource ID로 클릭
CLICK_DESC "설정 버튼"          # Content Description으로 클릭
```

### 길게 누르기
```
LONG_CLICK 540 960             # 좌표에서 1초 길게 누르기
LONG_CLICK 540 960 2000        # 좌표에서 2초 길게 누르기
LONG_CLICK_TEXT "항목" 1500     # 텍스트 요소 1.5초 길게 누르기
```

### 스크롤 & 스와이프
```
SCROLL UP                      # 위로 1회 스크롤
SCROLL DOWN 3                  # 아래로 3회 스크롤
SCROLL LEFT                    # 좌로 스크롤
SCROLL RIGHT                   # 우로 스크롤
SWIPE 100 500 900 500          # (100,500) → (900,500) 스와이프
SWIPE 100 500 900 500 500      # 500ms 동안 스와이프
```

### 텍스트 입력
```
INPUT "검색어 입력"              # 포커스된 입력 필드에 텍스트 입력
```

### 대기
```
WAIT 2000                      # 2초 대기
WAIT_FOR "완료" 5000            # "완료" 텍스트가 나타날 때까지 대기 (최대 5초)
```

### 네비게이션
```
HOME                           # 홈 버튼
BACK                           # 뒤로가기
RECENTS                        # 최근 앱 목록
```

### 앱 실행
```
LAUNCH "com.android.settings"  # 패키지명으로 앱 실행
```

### 반복
```
REPEAT 5                       # 5회 반복 시작
  SCROLL DOWN
  WAIT 500
END_REPEAT                     # 반복 종료

REPEAT 0                       # 무한 반복 (수동 중지 필요)
  CLICK 540 960
  WAIT 1000
END_REPEAT
```

### 핀치 줌
```
PINCH_IN 540 960               # 중심 (540,960)에서 줌인
PINCH_OUT 540 960 3.0          # 중심에서 줌아웃 (scale 3.0)
```

### 기타
```
TOAST "작업 완료!"              # 토스트 메시지 표시
SCREENSHOT                     # 스크린샷 저장
# 이것은 주석입니다             # 주석 (실행 안됨)
// 이것도 주석입니다
```

---

## 빌드 & 설치

1. Android Studio에서 프로젝트 열기
2. `Sync Gradle` 실행
3. 기기 또는 에뮬레이터에 빌드 & 실행
4. 앱 실행 후 **접근성 서비스 활성화** 필요:
   - 설정 → 접근성 → AutoTouch → 사용

---

## 사용 방법

1. 앱 실행 → 접근성 서비스 활성화
2. **파일 로드** 또는 **샘플** 버튼으로 스크립트 로드 (또는 직접 입력)
3. **검증** 버튼으로 문법 확인
4. **실행** 버튼 → 백그라운드에서 스크립트 실행 시작
5. 알림창에서 실행 현황 확인, **일시정지/중지** 가능
6. 중지하면 자동으로 메인 화면으로 복귀

---

## 요구사항

- Android 8.0 (API 26) 이상
- 접근성 서비스 권한
- 알림 권한 (Android 13+)
