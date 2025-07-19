# ClojureScriptの学習用リポジトリ

タスク管理アプリの作成を通して、モダンなClojureScriptの開発手法を学ぶことを目的としています。

## 前提

- Node.js 18+ and npm
- Java 11+
- VSCode on MacOS (using `code` command to open files)
- Basic ClojureScript knowledge

## ステップ1: プロジェクト作成

```sh
# task-managerディレクトリが作成される
npx create-cljs-project task-manager
cd task-manager
git init
code .gitignore # 以下を追記
```

```text
.clj-kondo/.cache/
.lsp/.cache/
```

以降は、適宜Git管理でコミットしてください。

## ステップ2: 依存関係ファイルの記述

```sh
code deps.edn # 新規作成して以下を記述
```

```clojure
{:paths ["src/main" "src/dev"]
 :deps {org.clojure/clojure {:mvn/version "1.11.1"}
        org.clojure/clojurescript {:mvn/version "1.11.60"}
        thheller/shadow-cljs {:mvn/version "2.28.18"}

        ;; RFX for React 19 compatibility
        io.factorhouse/rfx {:mvn/version "0.1.13"}
        io.factorhouse/re-frame-bridge {:mvn/version "0.1.13"}
        io.factorhouse/hsx {:mvn/version "0.1.13"}

        ;; Styling
        thheller/shadow-css {:mvn/version "LATEST"}
        arttuka/reagent-material-ui {:mvn/version "5.11.12-0"}

        ;; Routing
        metosin/reitit {:mvn/version "0.7.0-alpha7"}}}
```

```sh
code shadow-cljs.edn # 開いて以下の内容にする
```

```clojure
{:source-paths ["src/main" "src/dev" "src/test"]
 :dependencies [[binaryage/devtools "1.0.7"]
                [day8.re-frame/re-frame-10x "1.6.0"]]

 :dev-http {8080 "public"
            8081 "out/test"}

 :builds
 {:app {:target :browser
        :output-dir "public/js"
        :asset-path "/js"
        :modules {:main {:init-fn task-manager.core/init!}}

        :devtools {:before-load task-manager.core/stop
                   :after-load task-manager.core/start
                   :watch-dir "public"
                   :preloads [devtools.preload
                              day8.re-frame-10x.preload]}

        :compiler-options {:source-map true
                           :source-map-include-sources-content true}}

  :test {:target :browser-test
         :test-dir "out/test"
         :ns-regexp "-test$"}

  :node-test {:target :node-test
              :output-to "out/node-tests.js"
              :autorun true}}}
```

### ステップ3: VSCode + Calvaの設定

```sh
mkdir .vscode
code .vscode/settings.json # 以下を記述
```

```json
{
  "calva.replConnectSequences": [
    {
      "name": "shadow-cljs",
      "projectType": "shadow-cljs",
      "cljsType": "shadow-cljs",
      "builds": [
        "app",
        "test"
      ]
    }
  ],
  "calva.customREPLCommandSnippets": [
    {
      "name": "Tap current form",
      "snippet": "(tap> $current-form)"
    },
    {
      "name": "Inspect app state",
      "snippet": "@re-frame.db/app-db"
    }
  ]
}
```

```sh
mkdir .clj-kondo
code .clj-kondo/config.edn
```