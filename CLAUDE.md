# BedrockSkinFixer

統合版(Geyser/Floodgate)のプレイヤーのスキンを GeyserMC の Global API から取得して適用し、正しく表示されるようにする Paper 用プラグイン(Paper 1.21 以降・Java 21 必須)。
リポジトリ: https://github.com/gorogoro-space/BedrockSkinFixer (GPL-3.0)

## 作業の進め方(必ず守ること)

- **設計が確定するまで実装しない。** 機能追加や仕様変更は、まず設計案(何を・なぜ・どう変えるか、影響範囲)を提示し、承認を得てからコードを書く。
- 判断が必要な点は、選択肢を示して質問する。勝手に決めない。
- やり取りは日本語で行う。新しく書くコードのコメントは日本語でよい。既存の英語のコメントは、頼まれない限り書き換えない。
- 変更は必要最小限にする。頼まれていないリファクタリングや機能追加はしない。
- 作業後は、変更・追加・削除したファイルの一覧と変更内容を報告する。
- 仕様を変えたら README.md と CLAUDE.md も合わせて更新する。
- `git push` の前には必ず確認を取る。コミットは意味のある単位で分ける。
- リポジトリへの初回の `git push` の前には、「GitHub で Watch 設定(Custom → Issues と Pull requests)はしましたか?」と日本語で確認する。自動 Watch は廃止されており、設定しないと issue や PR の通知が届かない。
- コミットメッセージや PR に `Co-Authored-By: Claude` などの署名を付けない。`.claude/` と `CLAUDE.local.md` は Git に入れない(`.git/info/exclude` で除外済み)。
- サーバーや開発 PC の環境情報(プラグインの構成・バージョン、設定ファイルの内容、JDK の場所など)は内部情報なので、Git に入るファイル(README.md・CLAUDE.md・コード・コミットメッセージ・issue・PR)に書かない。`CLAUDE.local.md` にだけ書く。
- 実装中に設計の抜けや穴に気づいたら、黙って対処せず報告して相談する。
- プルリクエストをチェックするときは、次の観点で確認して報告する。
  - 変更概要(何を・なぜ変えているか)
  - 脆弱性(権限チェックの漏れ、入力値の検証、外部 API の応答の扱いなど)
  - 安全面(再読み込み時の後始末、他プラグインの妨げ、Floodgate が無いときの動作など)
  - 性能(TPS など)の低下(高頻度イベントでの重い処理、メインスレッドでの同期 I/O や HTTP 通信、定期タスクの追加など。「最重要の設計方針」に沿っているか)

## 環境

- **Paper 1.21 以降に対応する。** `plugin.yml` の `api-version: '1.21'`(1.21 未満のサーバーでは読み込まれない)。勝手に変えない
  - コンパイルには対応する一番古い `io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT`(`compileOnly`)を使う。1.21.1 以降で追加された API はビルドの時点でエラーになり、古い 1.21.x のサーバーで `NoSuchMethodError` などになるのを防げる。勝手に上げない(上げるなら `api-version` と合わせて相談する)
  - 本番サーバーは新しい 1.21.x なので、1.21.0 との間で動作が変わった API(非推奨化・挙動の変更)に注意する
- **Java 21 必須。** `build.gradle.kts` の toolchain で JDK 21 を使い、Java 21 の class ファイルを出力する。勝手に変えない
  - paper-api 26.x は「JVM 25 以上専用」と宣言しているため、Java 21 で出力する設定ではビルドできない(1.21 系でビルドしている理由のひとつ)
- ビルド: Gradle(Kotlin DSL、wrapper 9.7.1)。`gradlew.bat clean build`(Windows)。**JDK 21 で実行する**(JDK の場所は `CLAUDE.local.md`)。「ビルドして」と言われたら常にクリーンビルドする。成果物は `build/libs/BedrockSkinFixer-<version>.jar`
  - IntelliJ の「アーティファクトのビルド」はクラスファイルが入らないことがある。必ず Gradle でビルドする
- バージョンは `gradle.properties` の `version` だけで管理する。`plugin.yml` の `version: '${version}'` は `processResources` でビルド時に置き換わる
- パッケージ: `space.gorogoro.bedrockskinfixer`(**すべて小文字**。大文字が混ざると plugin.yml の main と一致せず起動しない)
- 動作確認はサーバーを再起動して行う。PlugManX での読み込みは権限やコマンドの登録が不完全になることがある。スキンの表示は統合版の実機と Java 版の両方から確認する。jar を差し替えるときは先に古い jar を消す(同じ名前のプラグインが 2 つあると、どちらが読まれるかわからない)
- Floodgate(`org.geysermc.floodgate:api:2.2.5-SNAPSHOT`、`compileOnly`、リポジトリ `https://repo.opencollab.dev/main/`)。`plugin.yml` の `depend: [floodgate]`
- GeyserMC の Global API(`https://api.geysermc.org/v2/skin/<XUID>`)。応答は `value`・`signature`(署名付きの textures)などを持つ JSON で、スキンが未登録の XUID には `{}` が返る。JSON の解析は Paper に入っている Gson を使う
- サーバーと開発 PC の環境情報は `CLAUDE.local.md`(Git に入れない)を参照する

## 最重要の設計方針

### TPS に影響させない
- 高頻度イベント(PlayerInteractEvent、PlayerMoveEvent、AsyncPlayerChatEvent など)を使うときは、安い判定を先に行い、対象外なら即座に抜ける
- BlockPhysicsEvent、VehicleMoveEvent など発生頻度が極端に高いイベントは使わない
- メインスレッドでファイルや DB の同期 I/O、HTTP 通信をしない(起動時・リロード時に一度だけ行う小さなファイルの読み込みは除く)
- 設定ファイルを追加する場合は、起動時・リロード時に一度だけ解析して保持する
- 定期タスクは最小限にし、追加するときは頻度と理由を設計案に書く

### データの保存
- プラグインフォルダ以外には何も書き込まない

### 他プラグインとの関係
- 既存の機能を妨げないこと。イベントをキャンセルする範囲は必要最小限にする

## 機能仕様

(現在の実装の動作。仕様を変えたらここを更新する)

- **起動時(`onEnable`)**: `config.yml` が無ければ jar 内のものを保存する。足りない項目があれば `copyDefaults(true)` と `saveConfig()` で既定値を追記する。`join-delay-ticks`(既定 15)・`retry-count`(既定 2)・`retry-interval-seconds`(既定 5)を読む。`language`(既定 `en`)で選んだ `messages.<language>.*` を読み込んで保持する(jar には `en`・`ja` を入れてある。利用者が `messages.fr` などを書き足せばその言語も使える。その言語の項目が無ければ警告を出して `en` にする。足りない項目は jar 内の `en` で補う)。定期タスクは登録しない
- **メッセージ**: コマンドの返事とコンソールの警告は、すべて config.yml の `messages` から取る(コードに表示用の文字列を直書きしない)。`{player}`・`{reason}` を置き換える。`plugin.yml` のコマンドの説明は切り替えられないので英語
- **参加時(`PlayerJoinEvent`)**: Floodgate のプレイヤーでなければ即座に抜ける。統合版なら `join-delay-ticks` 後に、Java 版のアカウントとリンク済み(`FloodgatePlayer.isLinked()`)でなければ XUID でスキンを取得する
  - リンク済みの人は Java 版のアカウントのスキンを使うので、自動では適用しない
- **取得**: Java 標準の `HttpClient.sendAsync`(タイムアウト 10 秒)で Global API を呼び(`User-Agent: BedrockSkinFixer/<version> (+https://github.com/gorogoro-space/BedrockSkinFixer)` を付ける。相手が問題に気づいたときに連絡できるようにするため)、結果はメインスレッドに戻して処理する。`value` と `signature` があれば適用する。通信エラー・200 以外・`{}`(未登録)は失敗とし、参加時は `retry-interval-seconds` 秒ごとに `retry-count` 回まで取り直す。すべて失敗したら警告ログを出す
- **適用**: `PlayerProfile` の `textures` を設定して `player.setPlayerProfile()` を呼ぶ(Paper が周りに表示し直す)。既に同じ `value` なら何もしない
- 取得処理はプレイヤーごとに番号で管理し、新しい取得を始めたとき・退出したときに古いもの(再試行の待ちを含む)を無効にする。適用前にオンラインかを確かめる。無効化時は `shutdownNow()` で通信を打ち切る
- **`/skinfix [プレイヤー名]`**: **OP のみ**(`sender.isOp()`。コンソールも可)。権限は使わない。引数なしなら自分、ありならそのオンラインのプレイヤー(`getPlayerExact`)。統合版でなければ断る。リンク済みの人も対象にする。再試行はせず、結果を実行した人に知らせる
- スキンのデータはファイルに保存しない(メモリにも残さない)

## 過去にハマった点(他プロジェクトでの経験)

- `config.getString(path, "")` のように既定値を渡すと、jar 内 config.yml の既定値が参照されない。既定値なしで取得して null を判定すること
- plugin.yml で `default: true` にした権限でも、登録されないと Bukkit は「OP のみ」として扱う。全員向けの機能を権限で縛らない
- 乗客などのエンティティを `remove()` すると内部で降車イベント(`EntityDismountEvent`)が出る。これをキャンセルすると、消えたエンティティが乗ったまま残り、毎 tick 降ろそうとして重くなる

## ファイル構成

- `src/main/java/space/gorogoro/bedrockskinfixer/BedrockSkinFixer.java` — メインクラス。参加時の処理、`/skinfix`、スキンの取得と適用をすべて持つ
- `src/main/resources/plugin.yml` — プラグイン定義
- `src/main/resources/config.yml` — 取得までの待ち時間と取り直しの設定、メッセージの言語(`language`)と英語・日本語のメッセージ(`messages`)
- `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` — Gradle の設定(バージョンは `gradle.properties`)
