(ns build
  (:require [shadow.css.build :as cb]
            [clojure.java.io :as io]))

(defn css-release [& args]
  (let [build-state
        (-> (cb/start)
            (cb/index-path (io/file "src" "main") {})
            (cb/generate '{:ui {:entries [task-manager.styles]}})
            (cb/minify)
            (cb/write-outputs-to (io/file "public" "css")))]
    (doseq [mod (vals (:chunks build-state))
            {:keys [warning-type] :as warning} (:warnings mod)]
      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))))