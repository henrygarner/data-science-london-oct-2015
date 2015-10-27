(ns example.core
  (:require [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [tesser.core :as t]
            [tesser.hadoop :as h]
            [tesser.math :as m]
            [parkour.io.text :as text]
            [parkour.tool :as tool])
  (:import [org.HdrHistogram EncodableHistogram
                             DoubleHistogram
            DoubleHistogramIterationValue])
  (:gen-class))

(defn load-data [file]
  (->> (io/resource file)
       (io/reader)
       (line-seq)
       (map read-string)))

(defn relevant? [x]
  (> (:b x) 40))

(defn convert-currency [{:keys [fx] :as x}]
  (-> x
      (update-in [:a] / fx)
      (update-in [:b] / fx)))

(defn assign-score [x]
  (let [score (->> (select-keys x [:a :b])
                   (vals)
                   (reduce +))]
    (assoc x :score score)))


(->> (load-data "data.edn")
     (filter relevant?)
     (map convert-currency)
     (map assign-score))

(defn process [data]
  (->> (filter relevant? data)
       (map convert-currency)
       (map assign-score)))

(process (load-data "data.edn"))


(def v [1 2 3 4])
;; #'user/v

(type v)
;; clojure.lang.PersistentVector

(type (map inc v))
;; clojure.lang.LazySeq

(type (mapv inc v))
;; clojure.lang.PersistentVector


(def xform
  (comp (filter relevant?)
     (map convert-currency)
     (map assign-score)))

(sequence xform (load-data "data.edn"))


(->> (load-data "data.edn")
     (sequence (comp xform (take 2))))

(->> (load-data "data.edn")
     (sequence (comp xform (map :score))))

(->> (load-data "data.edn")
     (sequence (comp xform (map :score)))
     (reduce +))

(->> (load-data "data.edn")
     (transduce (comp xform (map :score)) +))

(+)
;; 0

(+ 42)
;; 42

(+ 21 21)
;; 42

(conj)
;; []

(conj [42])
;; [42]

(conj [21] 21)
;; [21 21]


(defn hist-iqr
  ;; Zero arity init
  ([] (DoubleHistogram. 1e8 3))
  
  ;; Two arity step
  ([hist x]
   (doto hist
     (.recordValue x)))

  ;; Single arity complete
  ([hist]
   (vector (.getValueAtPercentile hist 25)
           (.getValueAtPercentile hist 75))))

(->> (load-data "data.edn")
     (transduce (comp xform (map :score)) hist-iqr))


(defn iqr-sequence [xform data]
  (let [[from to] (->> data
                       (transduce (comp xform (map :score)) hist-iqr))]
    (->> data
         (sequence (comp xform (filter #(<= from (:score %) to)))))))


(defn mean-step
  ([] {:sum 0 :count 0})
  ([accum x]
   (-> (update-in accum [:count] inc)
       (update-in [:sum] + x)))
  ([{:keys [sum count]}]
   (/ sum count)))

(->> (load-data "data.edn")
     (transduce (comp xform (map :score)) mean-step))


(defn iqr-mean [xform data]
  (let [[from to] (->> data
                       (transduce (comp xform (map :score)) hist-iqr))]
    (->> data
         (transduce (comp xform
                     (filter #(<= from (:score %) to))
                     (map :score))
                    mean-step))))

(iqr-mean xform (load-data "data.edn"))

(defn mean-combiner
  ;; Combiner is used for init value
  ([] {:sum 0 :count 0})
  ([a b]
   (merge-with + a b)))

(->> (load-data "data.edn")
     (into [] (comp xform (map :score)))
     (r/fold mean-combiner mean-step))


(def scorer
  (comp xform (map :score)))

(->> (load-data "data.edn")
     (into [] scorer)
     (r/fold mean-combiner mean-step))

(->> (load-data "data.edn")
     (r/fold mean-combiner (scorer mean-step)))


(defn filter' [pred]
  (fn [rf]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result input]
       (if (pred input)
         (rf result input)
         result)))))

(transduce (filter' even?) conj [1 2 3 4 5 6])

;; [2 4 6]

(def mean-fold
  (->> (t/filter relevant?)
       (t/map convert-currency)
       (t/map assign-score)
       (t/map :score)
       (m/mean)))

(-> (t/chunk 1024 (load-data "data.edn"))
    (t/tesser mean-fold))

(def iqr-transform
  {:reducer-identity (fn [] (DoubleHistogram. 1e8 3))
   :reducer (fn [hist x]
              (doto hist
                (.recordValue x)))
   :post-reducer identity
   :combiner-identity (fn [] (DoubleHistogram. 1e8 3))
   :combiner (fn [a b]
               (doto a (.add b)))
   :post-combiner (fn [hist]
                    (vector (.getValueAtPercentile hist 25)
                            (.getValueAtPercentile hist 75)))})

(def iqr-fold
  (->> (t/filter relevant?)
       (t/map convert-currency)
       (t/map assign-score)
       (t/map :score)
       (t/fold iqr-transform)))

(-> (t/chunk 1024 (load-data "data.edn"))
    (t/tesser iqr-fold))

;; [175.0 240.0]


(def multi-iqr-fold
  (->> (t/filter relevant?)
       (t/map convert-currency)
       ;; Facet runs fold on all keys in a map
       (t/map #(select-keys % [:measure-a :measure-b]))
       (t/facet)
       (t/fold iqr-transform)))

(-> (t/chunk 1024 (load-data "data.edn"))
    (t/tesser multi-iqr-fold))

;; {:measure-a [100.0 112.5] :measure-b [62.5 140.09375]}

(def fused-fold
  (->> (t/filter relevant?)
       (t/map convert-currency)
       (t/map assign-score)
       ;; Fuse runs different named folds
       (t/fuse {:count (t/count)
                :iqr   (->> (t/map :score)
                            (t/fold iqr-transform))})))

(-> (t/chunk 1024 (load-data "data.edn"))
    (t/tesser fused-fold))



(defn calculate-coefficients [{:keys [covariance variance-x
                                      mean-x mean-y]}]
  (let [slope (/ covariance variance-x)]
    {:intercept (- mean-y (* mean-x slope))
     :slope slope}))

(defn linear-regression [fx fy fold]
  (->> fold
       (t/fuse {:covariance (m/covariance fx fy)
                :variance-x (m/variance (t/map fx))
                :mean-x (m/mean (t/map fx))
                :mean-y (m/mean (t/map fx))})
       (t/post-combine calculate-coefficients)))

(def linear-regression-fold
  (->> (t/filter relevant?)
       (t/map convert-currency)
       (linear-regression :a :b)))

(-> (t/chunk 1024 (load-data "data.edn"))
    (t/tesser linear-regression-fold))


(defn rand-file [path]
  (io/file path (str (long (rand 0x100000000)))))

(defn linear-regression-dfold [fx fy]
  (->> (t/map read-string)
       (t/filter relevant?)
       (t/map convert-currency)
       (linear-regression fx fy)))

(defn tool [conf input-file work-dir]
  (let [dseq (text/dseq input-file)]
    (h/fold conf dseq (rand-file work-dir)
            #'linear-regression-dfold
            :a :b)))

(defn -main [& args]
  (System/exit (tool/run tool args)))
