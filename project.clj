(defproject
  suprematic/csi  "0.0.1"
  :plugins [[lein-cljsbuild "1.1.5"]
            [lein-ancient "0.6.10"]
            [lein-figwheel "0.5.14"]]

  :hooks   [leiningen.cljsbuild]

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.293"]
                 [org.clojure/core.async "0.2.395"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [com.cognitect/transit-cljs "0.8.256"]

                 [reagent "0.6.2"]
                 [re-frame "0.9.4"]]

  :figwheel {:server-port 3450}

  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src"]

                :figwheel {:on-jsload "csi.demo.ui/render"}

                :compiler {:main csi.demo.ui
                           :optimizations :none
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/application/application.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true
                           :preloads [devtools.preload]}}]}

  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.4"]
                                  [figwheel-sidecar "0.5.13"]
                                  [com.cemerick/piggieback "0.2.2"]]
                   :source-paths ["src"]
                   :clean-targets ^{:protect false} ["public/js/compiled" :target-path]}})
