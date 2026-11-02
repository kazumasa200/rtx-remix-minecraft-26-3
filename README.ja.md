# RTX Remix for Minecraft 26.3

[English](README.md) | **日本語**

GeForce RTX の GPU で、Minecraft Java Edition 26.3 をリアルタイムのパストレーシングで遊べるようにする Fabric の Mod です。描画には NVIDIA RTX Remix を使います。

このリポジトリは、NVIDIA の [RTX Remix for Minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft)（Minecraft 1.21.1 向け）を有志がフォークしたものです。26.3 に移植し、空、雲、レンズの効果、雨を独自に作り込んでいます。NVIDIA はこのフォークの保守もサポートもしていません。不具合は上流ではなく、[このリポジトリの Issues](https://github.com/kazumasa200/rtx-remix-minecraft-26-3/issues) に報告してください。

![アンバウンドの雲と太陽の光芒、水面のきらめき](docs/images/water-glint.webp)

| ![桜に囲まれた樹上の家と、青空に浮かぶアンバウンドの雲](docs/images/treehouse-clouds.webp) | ![高層ビルの間に差す日の光と光芒](docs/images/skyscrapers-sun.webp) |
|---|---|
| アンバウンドの雲と空 | 高層ビルの間の日差し |
| ![朝焼けに染まる雲と水面](docs/images/sunrise-water.webp) | ![雪の森のあいだを流れる川に映る雲](docs/images/snowy-river.webp) |
| 朝焼けの水面 | 雪の川に映る雲 |
| ![雨に濡れて建物を映す街の通り](docs/images/rainy-street.webp) | ![夜の濡れた道路に映る街灯](docs/images/rainy-night-street.webp) |
| 雨に濡れた通り | 夜の雨と街灯の照り返し |
| ![夜の村の家を照らす松明](docs/images/village-torches.webp) | ![雪原の上のオーロラと月](docs/images/aurora-snow.webp) |
| 夜の村を照らす松明 | 雪原の上のオーロラと月 |
| ![霧の夜、水辺の向こうに灯る街の明かり](docs/images/misty-city-night.webp) | ![夜の雨に濡れる架線と灯り](docs/images/night-rain-wires.webp) |
| 霧の夜の水辺の街 | 夜の雨と架線 |
| ![浅い池の底に揺れる日の光](docs/images/pond-caustics.webp) | ![水中から見た海の底の光の揺らぎ](docs/images/underwater-caustics.webp) |
| 池の底の光の揺らぎ | 水中から見た海の底の光 |
| ![街と海の上に広がる夕焼けのアンバウンドの雲](docs/images/sunset-clouds-city.webp) | ![雨の街を見下ろす塔の灯りと、レンズフレア](docs/images/rain-tower-flare.webp) |
| 夕焼け雲と街 | 雨の中のレンズフレア |
| ![雨の公園の小道に並ぶ街灯と、濡れた地面の照り返し](docs/images/rainy-park-lamps.webp) | ![夜の霧の中で灯る公園の街灯と、レンズフレア](docs/images/night-park-fog.webp) |
| 雨の公園の小道と街灯 | 霧の夜の公園の街灯 |
| ![夜の川辺の竹と灯り、明かりのともる高層ビル](docs/images/bamboo-river-towers.webp) | ![霧の夜、明かりのともるビルに囲まれた街の広場を見上げる](docs/images/foggy-city-plaza.webp) |
| 夜の川辺の竹と高層ビル | 霧の夜の街の広場 |

海と樹上の家のスクリーンショットは[ショーケースワールド](#ショーケースワールドとアートパック)と RTX アートパックで、ほかは別のワールドとリソースパックで撮りました。

## このフォークで加えたもの

- Minecraft 26.3 への移植: バニラのレンダーステートとフレームグラフの仕組みに合わせて移植しました。
- 物理に沿った空: エアロゾルとオゾンを含む実測の値と多重散乱で大気を計算するので、晴れた空は天頂ほど濃い青に、地平線ほど白くなります。雨上がりの虹、高い空の巻雲、北のオーロラも出ます。
- 立体的な雲: アンバウンドは地平線まで画素ごとに描く柔らかい雲で、日の当たる上面は白く、底は灰色になり、地面に影を落とします。雲の中にも飛び込めます。リイマジンドは大きく丸いブロック状の雲です。
- レンズの効果: 照準の先にピントを合わせる軽い被写界深度、レンズフレアと太陽の光芒、遠景のかすみ、波立つ水面のきらめきを加えました。
- 雨: 開けた地面に水たまりができ、遠景は曇り空にかすみます。
- 遠景の地形: Distant Horizons 3.3.4 を入れると、描画距離の外の地形もパストレーシングで描きます（試験的）。
- 光の多い場所での軽量化: シーランタンで照らした街のように光源が数千を超える所では、約 4,000 個までに絞って描くので、フレームレートが落ちません。

設定の一覧は [Settings](docs/SETTINGS.md)、リリースごとの変更は [CHANGELOG.md](CHANGELOG.md) にあります（どちらも英語）。

## はじめに知っておいてほしいこと

これはベータ版です。上流は技術デモとして作られ、[BlockWorks](https://www.blockworks.uk/) がそのプロジェクトのために作ったショーケースワールド（下の「[ショーケースワールドとアートパック](#ショーケースワールドとアートパック)」）で調整されています。そのため、このワールドに出てくる場所で、いちばんきれいに見え、軽く動きます。このフォークは RTX 5090 を積んだ Windows のパソコン 1 台で開発と確認をしています。[既知の問題](https://github.com/kazumasa200/rtx-remix-minecraft-26-3/issues)があり、まだ見つかっていない不具合もきっとあります。

ほかより手が回っていないところが 3 つあります。

- ほかの Mod との組み合わせ: ほかの Mod と一緒に試した範囲は狭く、描画やチャンクの読み込みを変える Mod とは競合しやすいはずです。一緒に確かめているのは Distant Horizons 3.3.4 だけです。
- 通しのプレイ: ショーケースに出てこないブロック、モブ、ディメンション、場面にはあまり手が入っておらず、見た目がおかしかったり重かったりするものがあります。
- ほかのバージョン: 対応は Minecraft 26.3 だけです。1.21.1 で遊ぶなら[上流](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft)を使ってください。

おかしな所を見つけたら[報告](docs/PLAYING.md#reporting-a-bug)してください。直せる方は「[開発に参加する](#開発に参加する)」を見てください。

## ダウンロード

[Releases のページ](https://github.com/kazumasa200/rtx-remix-minecraft-26-3/releases/latest)からリリースの ZIP をダウンロードします。

## 3 ステップでインストール

1. リリースの ZIP を展開する
2. `install.bat` をダブルクリックし、メニューに沿って進める
3. ランチャーで **NVIDIA RTX Remix for Minecraft** のプロファイルを起動する

ランチャーの選び方や困ったときの対処を含む詳しい手順は [Playing](docs/PLAYING.md)（英語）にあります。

## 動かすのに必要なもの

- Windows 10 か 11（x64）。このフォークのリリースに ARM64 版はありません。
- Minecraft Java Edition 26.3 と、それを持っている Microsoft アカウント
- GeForce RTX 30 シリーズ以降の GPU と、NVIDIA ドライバー 616.92 以降
- インストール中に約 5 GB の空き容量（インストール後に使うのは約 3 GB）
- インストーラーを動かすあいだのインターネット接続
- 16 GB のメモリを推奨

Java 25、Fabric、RTX Remix のランタイムはインストーラーが入れます。

ソースからビルドして動かすときに必要なものは [Getting Started](docs/GETTING_STARTED.md#prerequisites)（英語）にあります。Minecraft: Java Edition を持っている Microsoft アカウントでサインインした Prism Launcher も必要です。

## クラッシュレポートとプライバシー

Mod 自体はデータを集めません。同梱の RTX Remix ランタイムは、セッションの基本的な情報とクラッシュレポートを NVIDIA に送ることがあります。どちらも初回起動時の First Use Guide で聞かれ、初期値はどちらもオンです。RTX Remix Graphics Settings メニューの Permissions タブでいつでも変えられます。送られる内容は [Crash Reports And Privacy](docs/PLAYING.md#crash-reports-and-privacy)（英語）にあります。

## できること

ブロックもモブもアイテムも、パストレーシングで照らします。光は映画のレンダラーと同じように壁で跳ね返り、ガラスを抜け、松明からあふれます。影や反射、隣のブロックに映る色は、描き込むのではなく計算で出します。

見た目はゲームの状況に合わせて変わります。時刻、天気、水中のそれぞれに合った光になり、マップの作者は場所ごとに見た目を付けられます。何かをオンにする必要はありません。

新しいブロックの Copper Beacon は、置くことも持ち歩くこともできる、一方向を照らすランプです。その光の筋は本物の光として、壁や雨やそこに立つものを照らします。

歩いて記録した道筋を、あとからカメラで再生できます。動画の撮影や、変更前後の見比べに使えます。

描画距離、フレームレート、空、雲、レンズ、光、露出、素材は、設定ファイルを触らずにゲームのオプション画面で変えられます。画質の段階は Remix の Graphics Settings を開くボタンから選べます。

## Windows の Vulkan 表示

Windows では表示用のウィンドウを分け、一部の環境で Vulkan の起動に失敗する問題を避けます。画質は変わりません。従来の表示方式が動く環境でそれを使う場合は、ランチャーの JVM 引数に `-Drtxremix.presentation=parent` を追加して再起動してください。詳しくは [Tuning](docs/TUNING.md#presentation) を参照してください。

## Sodium との互換性

Sodium 0.9.2 を入れると、Sodium の最適化されたチャンク構築を使って地形をパストレーシング用に取り込みます。Sodium は必須ではなく、同梱もしていません。他のバージョンでは取り込み用の API を検証していないため、Minecraft の起動を停止します。

## ショーケースワールドとアートパック

Mod と一緒に使える追加のデータが 2 つあります。どちらも入れなくても動きます。[`tools/`](tools/README.md) の開発用ランチャーは、初めて動かしたときに両方をダウンロードし、パックを有効にしてワールドに入ります。

- **ショーケースワールド**はレンダラーの調整に使ったマップです。BlockWorks がこのプロジェクトのために作りました（[ウェブサイト](https://www.blockworks.uk/)、[YouTube](https://www.youtube.com/@BlockWorksYT)、[X](https://x.com/BlockWorksYT)、[Instagram](https://www.instagram.com/blockworks/)）。
- **RTX アートパック**は、NVIDIA のアートチームが作ったテクスチャのリソースパックです。ノーマルマップとスペキュラーマップは [LabPBR の規格](https://shaderlabs.org/wiki/LabPBR_Material_Standard)に沿っているので、LabPBR を読むほかのレンダラーやシェーダーパックでも使えるかもしれませんが、試したのはこの Mod だけです。`_sss` のサブサーフェスマップはこの Mod 独自のもので、[Resource Packs](docs/RESOURCE_PACKS.md)（英語）で説明しています。

## 目的別の案内

ガイドはどれも英語です。

| やりたいこと | 読むもの |
|---|---|
| パストレーシングで遊ぶ | [Playing](docs/PLAYING.md) |
| 既知の問題を見る | [Issues](https://github.com/kazumasa200/rtx-remix-minecraft-26-3/issues) |
| サーバーで遊ぶ | [Multiplayer](docs/PLAYING.md#multiplayer) |
| レンダラーを設定する | [Settings](docs/SETTINGS.md) |
| コマンド、キー、Mod が加えるブロックを調べる | [In-Game Guide](docs/IN_GAME_GUIDE.md) |
| 自分のマップに専用の光を付ける | [Gameplay-driven profiles](packaging/rtx-remix/mods/minecraft-logic/README.md) |
| このレンダラーに合うテクスチャパックを作る | [Resource Packs](docs/RESOURCE_PACKS.md) |
| カメラの移動撮影を記録する | [Camera Paths](docs/CAMERA_PATHS.md) |
| インストール、修復、アンインストールを詳しく知る | [packaging/INSTALL.md](packaging/INSTALL.md) |
| ソースからビルドして動かす | [Getting Started](docs/GETTING_STARTED.md) |
| 開発者向けの調整値を変える | [Tuning](docs/TUNING.md) |
| 描画やネイティブ連携の問題を調べる | [Rendering Diagnostics](docs/RENDERING_DIAGNOSTICS.md) |
| 変更を送る | [CONTRIBUTING.md](CONTRIBUTING.md) |
| 不具合を報告する | [Reporting a Bug](docs/PLAYING.md#reporting-a-bug) |
| 脆弱性を報告する | [SECURITY.md](SECURITY.md) |
| 変更の履歴を見る | [CHANGELOG.md](CHANGELOG.md) |

## ソースとツールの配置

| パス | 中身 |
|---|---|
| `src/` | Mod の実装と JVM のテスト |
| `rtxremix-java/` | RTX Remix の C API に対する、Minecraft に依存しない Java のファサードと JNI ブリッジ |
| `tools/` | 開発用ランチャー、ベンチマーク、プロファイル、SDK の配置、開発用のインストール |
| `packman/` | Packman の起動スクリプトと、ランタイムのパッケージのバージョンの指定 |
| `packaging/` | 配布用のインストーラー、ランタイムの初期設定、配布物の中身 |
| `docs/` | 遊ぶ人、作る人、ソースからビルドする人向けのガイド |

NVIDIA は上流のリリースと CI の道具の一部をこのリポジトリの外で管理しています。これらの道具は、公開されている Gradle のビルド、開発用ツール、インストールした Mod には必要ありません。

## 開発に参加する

外部からの貢献を歓迎します。とくに助かるのは、上に挙げた手の回っていないところです。ほかの Mod との互換性、通しのプレイで出会うブロックやモブや場所への対応、こちらにない機材での性能などです。このリポジトリをフォークし、開発用のブランチを作ってプルリクエストを送ってください。各コミットには `git commit -s` で署名を付け、[Developer Certificate of Origin](https://developercertificate.org/) に同意したことを示してください。CLA への署名はいりません。コミットと署名の名前は GitHub のハンドル名で、メールアドレスは GitHub の no-reply アドレスでかまいません。詳しい決まりは [CONTRIBUTING.md](CONTRIBUTING.md)（英語）にあります。

## ライセンス

このプロジェクトは Apache License 2.0（`Apache-2.0`）で公開しています。[LICENSE](LICENSE) を見てください。帰属の表示は [NOTICE](NOTICE) に、同梱しているサードパーティのソフトウェアは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) にあります。リリースの ZIP には RTX Remix のランタイムも入っており、その NVIDIA の部分にはそれぞれのライセンス条項が適用されます。THIRD_PARTY_NOTICES.md に一覧があります。

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. Minecraft is a trademark of Mojang Synergies AB.

NVIDIA, GeForce, and RTX are trademarks and/or registered trademarks of NVIDIA Corporation in the U.S. and other countries.
