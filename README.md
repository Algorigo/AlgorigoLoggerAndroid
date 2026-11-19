# AlgorigoLogger

`java.util.logging` 기반 Android 로깅 라이브러리입니다. 로그 태그를 Kotlin `object` 계층으로 정의하고, 같은 로그를 logcat, 로테이션 파일(S3 업로드 가능), AWS CloudWatch Logs, Datadog으로 보낼 수 있습니다.

- Maven 좌표: `com.algorigo.library:algorigo-logger` (Maven Central)
- 패키지: `com.algorigo.logger`
- 최소 Android 버전: 7.0 (API 24), Java 17

## 설치

배포된 POM에는 의존성이 들어 있지 않습니다. 사용하는 기능에 맞춰 아래 라이브러리를 앱에 직접 추가해야 합니다.

```kotlin
dependencies {
    implementation("com.algorigo.library:algorigo-logger:1.2.6")
    implementation(kotlin("reflect"))                                   // 항상 필요 (Tag 이름을 리플렉션으로 만듭니다)

    // RotatingFileHandler, CloudWatchHandler 를 쓸 때
    implementation("io.reactivex.rxjava3:rxjava:3.1.12")
    implementation("com.jakewharton.rxrelay3:rxrelay:3.0.1")
    implementation("com.amazonaws:aws-android-sdk-logs:2.81.1")       // CloudWatch
    implementation("com.amazonaws:aws-android-sdk-s3:2.81.1")         // S3 업로드
    implementation("com.amazonaws:aws-android-sdk-cognito:2.20.1")    // Cognito identity pool 자격 증명

    // DataDogHandler 를 쓸 때
    implementation("com.datadoghq:dd-sdk-android-logs:3.12.1")
}
```

라이브러리 manifest가 `INTERNET` 권한을 추가합니다. 앱에서 R8 난독화를 쓰면 태그 이름도 난독화되므로, `Tag`를 상속한 클래스는 keep 규칙으로 남기세요.

## 사용 방법

### 1. 태그 정의

`Tag`를 상속한 `object`를 중첩해서 정의합니다. 중첩 구조가 그대로 로거 이름(`LogTag.Network.Http`)과 계층이 됩니다.

```kotlin
object LogTag : Tag() {
    object Network : Tag() {
        object Http : Tag()
    }
    object Ble : Tag()
}
```

### 2. 초기화와 로그 출력

상위 태그 로거에 레벨과 handler를 설정하면 하위 태그에도 모두 적용됩니다.

```kotlin
LogManager.initTags(LogTag)                 // 태그 계층 전체 등록
LogManager.removeRootAndroidHandler()       // Android 기본 handler 제거 (없으면 NOTICE 이상이 두 번 출력됩니다)

LogManager.getLogger(LogTag).apply {
    level = Level.VERBOSE.level
    addHandler(AndroidHandler(Level.DEBUG))
}

L.info(LogTag.Network, "connected")
L.error(LogTag.Ble, "connection failed", throwable)
```

- 레벨은 `VERBOSE`, `DEBUG`, `INFO`, `NOTICE`, `WARNING`, `ERROR`, `ASSERTS` 순입니다.
- `L.*`은 메시지 끝에 호출 위치(`파일명:줄번호`)를 붙입니다. `L`을 감싸는 함수를 만들면 `depth`로 건너뛸 호출 단계를 지정하세요.
- `L.*`은 레벨이 꺼져 있어도 메시지와 호출 위치를 계산합니다. 자주 호출되는 곳에서는 레벨을 먼저 확인하세요.

### 3. 여러 곳으로 보내기 (`AlgorigoLogHandler`)

`AlgorigoLogHandler`에 여러 handler를 붙이면 각 handler의 레벨에 맞춰 로그를 나눠 보냅니다.

```kotlin
val algorigoLogHandler = AlgorigoLogHandler()
LogManager.getLogger(LogTag).addHandler(algorigoLogHandler)

algorigoLogHandler.addHandler(AndroidHandler(Level.VERBOSE))

// filesDir/logs/log.txt 에 쓰고, 10MB 를 넘으면 파일을 돌려가며 최대 5개를 보관합니다
val fileHandler = RotatingFileHandler(context, relativePath = "logs/log.txt")
algorigoLogHandler.addHandler(fileHandler)
```

### 4. 로그 파일 S3 업로드

로테이션된 파일을 S3에 올리고, 올린 파일 이름 끝에 `s3`를 붙입니다. 실패하면 1분마다 다시 시도합니다.

```kotlin
fileHandler.registerS3Uploader(
    CredentialsProviderHolder.IdentityPoolProvider(context, identityPoolId, "ap-northeast-2"),
    Region.getRegion(Regions.AP_NORTHEAST_2),
    bucketName,
    KeyFormat("log_file/@yyyy/MM/dd@/app-log-@yyyy-MM-dd-HH-mm-ss@.log"),   // @...@ 안은 날짜 형식
)
```

### 5. CloudWatch Logs

```kotlin
val cloudWatchHandler = CloudWatchHandler(
    context,
    "/my-app/logs",              // log group (없으면 만듭니다)
    deviceId,                    // log stream (없으면 만듭니다)
    CredentialsProviderHolder.IdentityPoolProvider(context, identityPoolId, "ap-northeast-2"),
    Region.getRegion(Regions.AP_NORTHEAST_2),
    level = Level.INFO,
    logGroupRetentionDays = CloudWatchHandler.RetentionDays.week2,
)
algorigoLogHandler.addHandler(cloudWatchHandler)
```

로그는 기기의 SQLite DB에 쌓였다가 묶음으로 전송됩니다. 기본 전송 주기는 1분이고 최소 10초입니다. 전송에 실패한 묶음은 DB에 남아 있다가, 같은 실행 중에는 다시 보내지 않고 다음에 앱을 시작할 때 다시 전송합니다. 단, 보관 기간(`logGroupRetentionDays`)이 25일 이상이면 앱을 다시 시작할 때 전송되지 않은 로그가 모두 지워지는 버그가 있습니다. 자세한 내용은 [CLAUDE.md](CLAUDE.md)를 참고하세요.

### 6. Datadog

`DataDogLogDelegate`를 먼저 등록하고 태그를 초기화한 다음 `DataDogHandler`를 만듭니다. 순서가 다르면 `IllegalStateException`이 나거나 로그가 전송되지 않습니다.

```kotlin
LogManager.addDelegate(DataDogLogDelegate(context, clientToken, env = "dev", service = "my-app"))
LogManager.initTags(LogTag)
LogManager.getLogger(LogTag).addHandler(DataDogHandler())
```

`initTags`로 등록하지 않은 태그의 로그는 Datadog으로 전송되지 않습니다. Datadog SDK를 앱에서 이미 초기화했다면 `DataDogLogDelegate`의 `remoteLevel` 등 옵션은 적용되지 않습니다.

> 자격 증명(AWS 키, Cognito identity pool ID, Datadog client token)은 코드에 커밋하지 마세요. 샘플 앱에는 빈 문자열로 들어 있습니다.

## 빌드

```bash
./gradlew :algorigo_logger:build              # 라이브러리 빌드 (CI 와 동일)
./gradlew :algorigo_logger:assembleRelease    # AAR → algorigo_logger/build/outputs/aar/
./gradlew :app:installDebug                   # 샘플 앱 (logcat, 파일, S3, CloudWatch)
./gradlew :datadoglogapp:installDebug         # Datadog 샘플 앱
```

## 배포

버전은 `algorigo_logger/build.gradle.kts`의 `versionStr`입니다. git 브랜치 이름에 `master`가 들어가면 `<versionStr>`, 아니면 `<versionStr>-<사용자명>-SNAPSHOT`이 됩니다.

```bash
./gradlew :algorigo_logger:assembleRelease :algorigo_logger:publish   # → algorigo_logger/build/staging-deploy
```

`publish`는 미리 빌드된 AAR을 로컬 디렉터리 `build/staging-deploy`에 서명해서 배포합니다. 서명 키 설정(`signing.*` 속성)이 필요합니다. Maven Central 업로드는 CircleCI가 합니다.

| 브랜치 | CI job | 업로드 위치 |
| --- | --- | --- |
| `master` | `deploy_to_maven_central` | Central Publisher API (`staging-deploy`를 zip으로 묶어 업로드) |
| `release/*`, `hotfix/*` | `deploy` | `https://central.sonatype.com/repository/maven-snapshots` |

CI는 `GRADLE_PROPERTY_PATH`, `SECRET_KEY_RING_PATH` 환경 변수에서 `gradle.properties`와 서명 키를 내려받고, 업로드에는 `MAVEN_CENTRAL_TOKEN`을 씁니다.

## 프로젝트 구조

```
algorigo_logger/     배포되는 라이브러리
  LogManager.kt        Tag, Level, LogManager, L
  handler/             AlgorigoLogHandler, AndroidHandler, RotatingFileHandler, CloudWatchHandler, DataDogHandler
  formatter/           TimedLogFormatter, TimelessLogFormatter
  util/                CloudWatch 전송 큐(SQLite), S3 키 형식(KeyFormat)
  DataDogLogDelegate.kt, CredentialsProviderHolder.kt
app/                 샘플 앱 (logcat, 로테이션 파일 + S3, CloudWatch)
datadoglogapp/       Datadog 샘플 앱
```
