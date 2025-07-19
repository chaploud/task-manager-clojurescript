(ns repl
  (:require [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server.fs-watch :as fs-watch]
            [clojure.java.io :as io]
            [build]))

(defonce css-watch-ref (atom nil))

(defn start []
  (shadow/watch :app)
  (build/css-release)
  (reset! css-watch-ref
          (fs-watch/start
           {}
           [(io/file "src" "main")]
           ["cljs" "cljc" "clj"]
           (fn [_]
             (try
               (build/css-release)
               (catch Exception e
                 (prn [:css-failed e]))))))
  ::started)

(defn stop []
  (shadow/stop-worker :app)
  (when-let [css-watch @css-watch-ref]
    (fs-watch/stop css-watch))
  ::stopped) false?

(defn go []
  (stop)
  (start))