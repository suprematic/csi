(defproject suprematic/csi "0.4.0-SNAPSHOT"
  :description "ClojureScript interface (inspired by Erlang's Java Interface)"
  :url "https://github.com/suprematic/csi"
  :license {:name "Eclipse Public License - v1.0"
            :url  "https://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.439"]
                 [org.clojure/core.async "0.4.490"]
                 [org.clojure/core.match "0.3.0-alpha5"]
                 [com.cognitect/transit-cljs "0.8.256"]]

  :profiles
  {:dev
   {:dependencies [[binaryage/devtools "0.9.10"]
                   [cider/piggieback "0.4.0"]]
    :source-paths ["src"]
    :clean-targets ^{:protect false} ["public/js/compiled" :target-path]}})
