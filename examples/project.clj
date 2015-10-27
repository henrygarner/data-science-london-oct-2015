(defproject example "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [tesser.core "1.0.1"]
                 [tesser.math "1.0.1"]
                 [tesser.hadoop "1.0.2"]
                 [org.hdrhistogram/HdrHistogram "2.1.2"]]
  :profiles {:provided
             {:dependencies
              [[org.apache.hadoop/hadoop-client "2.4.1"]
               [org.apache.hadoop/hadoop-common "2.4.1"]
               [org.slf4j/slf4j-api "1.6.1"]
               [org.slf4j/slf4j-log4j12 "1.6.1"]
               [log4j "1.2.17"]]}})
